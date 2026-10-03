import { builtinEmojiItems, builtinEmojiContent } from './builtinEmoji';
import { localHeaders } from '../frontend-src/services/platform/localWeb';
import {
  parseChatAttachment,
  MAX_CHAT_ATTACHMENT_BYTES,
  type ChatAttachment,
} from '../frontend-src/services/chat/fileAttachment';
import { fileToOutgoingImageDataUrl } from '../frontend-src/services/chat/imageData';
import { captureVoiceStream } from '../frontend-src/services/voice/nvidiaNoise';
import { lobbyCaptureGate } from '../frontend-src/services/voice/lobbyCaptureGate';
import { MAX_VOICE_BYTES } from '../frontend-src/services/chat/voiceMessage';

export function uploadAttachment(
  file: File,
  recipient: string | undefined,
  signal: AbortSignal,
  progress: (percent: number) => void
): Promise<ChatAttachment> {
  if (!file.size || file.size > MAX_CHAT_ATTACHMENT_BYTES)
    return Promise.reject(new Error('文件必须为 1 B 至 64 MiB'));
  return localHeaders().then(
    (headers) =>
      new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        const query = new URLSearchParams({
          name: file.name,
          mime: file.type || 'application/octet-stream',
        });
        if (recipient) query.set('recipientId', recipient);
        xhr.open('POST', `/api/chat/upload?${query}`);
        xhr.timeout = 120000;
        for (const [key, value] of Object.entries(headers)) xhr.setRequestHeader(key, value);
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        const abort = () => xhr.abort();
        const fail = (message: string) => {
          signal.removeEventListener('abort', abort);
          reject(new Error(message));
        };
        xhr.onerror = () => fail('本地上传连接失败');
        xhr.ontimeout = () => fail('上传超时');
        xhr.onabort = () => fail('上传已取消');
        xhr.upload.onprogress = (event) => {
          if (!signal.aborted && event.lengthComputable)
            progress(Math.round((event.loaded * 100) / event.total));
        };
        xhr.onload = () => {
          signal.removeEventListener('abort', abort);
          try {
            const data = JSON.parse(xhr.responseText);
            if (xhr.status !== 200) throw new Error(data.error || `上传失败 HTTP ${xhr.status}`);
            const attachment = parseChatAttachment(data);
            if (!attachment) throw new Error('附件元数据无效');
            resolve(attachment);
          } catch (error) {
            reject(error);
          }
        };
        if (signal.aborted) {
          fail('上传已取消');
          return;
        }
        signal.addEventListener('abort', abort, { once: true });
        xhr.send(file);
      })
  );
}

type Hooks = {
  online: () => boolean;
  recipient: () => string | undefined;
  status: (message: string, error?: boolean) => void;
  file: (attachment: ChatAttachment, recipient?: string) => Promise<void>;
  image: (data: string, label: string, recipient?: string) => Promise<void>;
  text: (text: string, recipient?: string) => Promise<void>;
  voice: (blob: Blob, duration: number, recipient?: string) => Promise<void>;
};
export function setupComposer(hooks: Hooks) {
  const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
  let cancellable = false;
  let generation = 0,
    uploading = false,
    controller: AbortController | null = null;
  let recorder: MediaRecorder | null = null,
    release: (() => void) | null = null,
    timer: number | null = null;
  let recording = false;
  const ids = ['send-file', 'send-image', 'send-emoji', 'custom-emoji', 'record-voice'];
  function update() {
    ids.forEach((id) => {
      element<HTMLButtonElement>(id).disabled = !hooks.online() || uploading || recording;
    });
    element<HTMLButtonElement>('cancel-upload').hidden = !cancellable;
    element<HTMLButtonElement>('finish-voice').hidden = !recorder;
    element<HTMLButtonElement>('cancel-voice').hidden = !recording;
  }
  async function selected(file: File, image: boolean, emoji = false) {
    if (!hooks.online() || uploading || recording) return;
    const job = generation,
      recipient = hooks.recipient();
    uploading = true;
    controller = new AbortController();
    const signal = controller.signal;
    update();
    try {
      // Sniff source image signatures using the upstream validator; never render SVG/HTML.
      const data = image ? await fileToOutgoingImageDataUrl(file) : null;
      if (job !== generation || signal.aborted) return;
      if (data && file.size <= 2 * 1024 * 1024) {
        hooks.status('正在发送图片…');
        await hooks.image(data, emoji ? '[表情]' : '[图片]', recipient);
      } else {
        cancellable = true;
        update();
        hooks.status('正在上传到本地服务…');
        const attachment = await uploadAttachment(file, recipient, signal, (percent) =>
          hooks.status(`本地上传 ${percent}% · 尚未送达对端`)
        );
        if (job !== generation || signal.aborted || !hooks.online()) return;
        cancellable = false;
        update();
        await hooks.file(attachment, recipient);
      }
    } catch (error) {
      if (job === generation)
        hooks.status(String(error instanceof Error ? error.message : error), true);
    } finally {
      if (job === generation) {
        uploading = false;
        cancellable = false;
        controller = null;
        update();
      }
    }
  }
  for (const [button, input, image] of [
    ['send-file', 'file-input', false],
    ['send-image', 'image-input', true],
    ['custom-emoji', 'emoji-input', true],
  ] as const) {
    element<HTMLButtonElement>(button).onclick = () => element<HTMLInputElement>(input).click();
    element<HTMLInputElement>(input).onchange = () => {
      const picker = element<HTMLInputElement>(input);
      const file = picker.files?.[0];
      picker.value = '';
      if (file) void selected(file, image, input === 'emoji-input');
    };
  }
  element<HTMLButtonElement>('send-emoji').onclick = () => {
    element('emoji-panel').hidden = !element('emoji-panel').hidden;
  };
  for (const emoji of [
    '😀',
    '😂',
    '🥰',
    '😎',
    '🤔',
    '😭',
    '😡',
    '👍',
    '👎',
    '👏',
    '🙏',
    '❤️',
    '🎉',
    '🔥',
    '✅',
    '👀',
  ]) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = emoji;
    button.setAttribute('aria-label', `发送表情 ${emoji}`);
    button.onclick = () => {
      if (hooks.online() && !uploading && !recording)
        void hooks
          .text(emoji, hooks.recipient())
          .catch((error) => hooks.status(String(error), true));
    };
    element('emoji-grid').append(button);
  }
  for (const emoji of builtinEmojiItems) {
    const button = document.createElement('button');
    button.type = 'button';
    button.setAttribute('aria-label', `发送内置表情 ${emoji.name}`);
    const image = document.createElement('img');
    image.src = emoji.url;
    image.alt = emoji.name;
    image.loading = 'lazy';
    button.append(image);
    button.onclick = () => {
      if (hooks.online() && !uploading && !recording)
        void hooks
          .text(builtinEmojiContent(emoji.id), hooks.recipient())
          .catch((error) => hooks.status(String(error), true));
    };
    element('builtin-emoji-grid').append(button);
  }
  element<HTMLButtonElement>('cancel-upload').onclick = () => controller?.abort();
  function stop(cancel: boolean) {
    if (cancel) generation++;
    const active = recorder;
    recorder = null;
    if (timer !== null) clearInterval(timer);
    timer = null;
    if (active && active.state !== 'inactive') active.stop();
    active?.stream.getTracks().forEach((track) => track.stop());
    release?.();
    release = null;
    recording = false;
    update();
  }
  element<HTMLButtonElement>('record-voice').onclick = async () => {
    if (!hooks.online() || uploading || recording) return;
    const job = ++generation,
      recipient = hooks.recipient();
    recording = true;
    release = lobbyCaptureGate.suspend();
    const free = release;
    update();
    hooks.status('正在申请录音权限…');
    let stream: MediaStream | null = null;
    try {
      if (typeof MediaRecorder === 'undefined') throw new Error('此浏览器不支持语音录制');
      stream = await captureVoiceStream();
      if (job !== generation || !hooks.online()) {
        stream.getTracks().forEach((t) => t.stop());
        free();
        return;
      }
      const mime = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4'].find((type) =>
        MediaRecorder.isTypeSupported(type)
      );
      if (!mime) throw new Error('浏览器没有兼容的录音编码器');
      const active = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 16000 });
      recorder = active;
      const began = performance.now(),
        chunks: Blob[] = [];
      let bytes = 0;
      active.ondataavailable = (event) => {
        bytes += event.data.size;
        chunks.push(event.data);
        if (bytes > MAX_VOICE_BYTES) {
          stop(true);
          hooks.status('录音超过 2 MiB，已取消', true);
        }
      };
      active.onerror = () => {
        stop(true);
        hooks.status('录音失败，已取消', true);
      };
      stream.getAudioTracks().forEach((track) =>
        track.addEventListener(
          'ended',
          () => {
            if (recorder === active) stop(true);
          },
          { once: true }
        )
      );
      active.onstop = () => {
        if (job !== generation || !hooks.online()) return;
        const duration = Math.min(60, (performance.now() - began) / 1000);
        const blob = new Blob(chunks, { type: mime.split(';')[0] });
        if (duration < 0.5 || !blob.size || blob.size > MAX_VOICE_BYTES) {
          hooks.status('录音须为 0.5–60 秒且不超过 2 MiB', true);
          return;
        }
        uploading = true;
        update();
        void hooks
          .voice(blob, duration, recipient)
          .catch((error) => hooks.status(String(error), true))
          .finally(() => {
            if (job === generation) {
              uploading = false;
              update();
            }
          });
      };
      active.start(250);
      update();
      timer = window.setInterval(() => {
        const seconds = (performance.now() - began) / 1000;
        hooks.status(`录音 ${Math.floor(seconds)} / 60 秒 · 通话麦克风暂时静音`);
        if (seconds >= 60) stop(false);
      }, 100);
    } catch (error) {
      stream?.getTracks().forEach((t) => t.stop());
      free();
      if (job === generation) {
        stop(true);
        hooks.status(String(error), true);
      }
    }
  };
  element<HTMLButtonElement>('finish-voice').onclick = () => stop(false);
  element<HTMLButtonElement>('cancel-voice').onclick = () => {
    stop(true);
    hooks.status('录音已取消');
  };
  const cancel = () => {
    generation++;
    controller?.abort();
    uploading = false;
    cancellable = false;
    stop(true);
    element('emoji-panel').hidden = true;
  };
  window.addEventListener('pagehide', cancel);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && recording) stop(true);
  });
  window.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      if (recording) stop(true);
      controller?.abort();
      element('emoji-panel').hidden = true;
    }
  });
  return { update, cancel };
}
