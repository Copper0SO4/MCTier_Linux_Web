import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const bundle = await build({
  entryPoints: ['MCTier-Linux-Web/web/chatComposer.ts'],
  bundle: true,
  format: 'esm',
  write: false,
  plugins: [
    {
      name: 'browser-fixtures',
      setup(build) {
        build.onResolve(
          { filter: /services\/(platform\/localWeb|voice\/(nvidiaNoise|lobbyCaptureGate))$/ },
          (args) => ({ path: args.path, namespace: 'fixture' })
        );
        build.onLoad({ filter: /.*/, namespace: 'fixture' }, (args) => ({
          contents: args.path.endsWith('localWeb')
            ? 'export async function localHeaders(){return {"x-mctier-csrf":"nonce","x-mctier-client":"client"}}'
            : args.path.endsWith('nvidiaNoise')
              ? 'export function captureVoiceStream(){return globalThis.captureFixture()}'
              : 'export const lobbyCaptureGate={suspend(){let freed=false;globalThis.leases++;return ()=>{if(!freed){freed=true;globalThis.leases--}}}}',
          loader: 'js',
        }));
      },
    },
  ],
});
const { setupComposer, uploadAttachment } = await import(
  `data:text/javascript,${encodeURIComponent(bundle.outputFiles[0].text)}`
);

function fixture() {
  const elements = new Map();
  globalThis.document = {
    hidden: false,
    addEventListener() {},
    createElement() {
      return { setAttribute() {}, append() {} };
    },
    getElementById(id) {
      if (!elements.has(id))
        elements.set(id, {
          hidden: true,
          disabled: true,
          append() {},
          click() {},
          value: '',
          files: [],
        });
      return elements.get(id);
    },
  };
  globalThis.window = {
    addEventListener() {},
    setInterval() {
      return 1;
    },
    clearInterval() {},
  };
  globalThis.clearInterval = () => {};
  globalThis.leases = 0;
  const tracks = [
    {
      stop() {
        this.stopped = true;
      },
      addEventListener() {},
    },
  ];
  const stream = { getTracks: () => tracks, getAudioTracks: () => tracks };
  const calls = [];
  globalThis.MediaRecorder = class {
    static isTypeSupported() {
      return true;
    }
    constructor(stream) {
      this.stream = stream;
      this.state = 'inactive';
    }
    start() {
      this.state = 'recording';
    }
    stop() {
      this.state = 'inactive';
      queueMicrotask(() => {
        this.ondataavailable({ data: new Blob([new Uint8Array([0x1a, 0x45, 0xdf, 0xa3])]) });
        this.onstop();
      });
    }
  };
  const composer = setupComposer({
    online: () => true,
    recipient: () => 'recipient',
    status() {},
    file: async () => {},
    image: async () => {},
    text: async () => {},
    voice: async (...args) => calls.push(args),
  });
  return { composer, elements, tracks, stream, calls };
}

test('cancel while microphone permission is pending releases late tracks and never sends', async () => {
  const f = fixture();
  let resolve;
  globalThis.captureFixture = () => new Promise((r) => (resolve = r));
  const pending = f.elements.get('record-voice').onclick();
  assert.equal(globalThis.leases, 1);
  f.composer.cancel();
  resolve(f.stream);
  await pending;
  assert.equal(globalThis.leases, 0);
  assert.equal(f.tracks[0].stopped, true);
  assert.equal(f.calls.length, 0);
});
test('cancel active recording releases tracks and suppresses queued onstop delivery', async () => {
  const f = fixture();
  globalThis.captureFixture = async () => f.stream;
  await f.elements.get('record-voice').onclick();
  f.composer.cancel();
  await Promise.resolve();
  assert.equal(globalThis.leases, 0);
  assert.equal(f.tracks[0].stopped, true);
  assert.equal(f.calls.length, 0);
});
test('upload sends bytes with local authentication and validates returned metadata', async () => {
  let sent,
    headers = {},
    url;
  globalThis.XMLHttpRequest = class {
    upload = {};
    open(method, address) {
      assert.equal(method, 'POST');
      url = address;
    }
    setRequestHeader(k, v) {
      headers[k] = v;
    }
    send(file) {
      sent = file;
      this.status = 200;
      this.responseText = JSON.stringify({
        id: 'attachment-123456',
        name: 'safe.txt',
        mime: 'text/plain',
        size: 3,
      });
      this.onload();
    }
  };
  const file = { size: 3, name: 'safe.txt', type: 'text/plain' };
  const meta = await uploadAttachment(file, 'recipient', new AbortController().signal, () => {});
  assert.equal(sent, file);
  assert.match(url, /recipientId=recipient/);
  assert.equal(headers['x-mctier-csrf'], 'nonce');
  assert.equal(meta.size, 3);
  await assert.rejects(
    uploadAttachment(
      { ...file, size: 64 * 1024 * 1024 + 1 },
      undefined,
      new AbortController().signal,
      () => {}
    ),
    /64 MiB/
  );
});
test('finishing a valid recording sends once with captured recipient and frees the capture lease', async () => {
  const original = globalThis.performance;
  let now = 0;
  Object.defineProperty(globalThis, 'performance', {
    value: { now: () => now },
    configurable: true,
  });
  try {
    const f = fixture();
    globalThis.captureFixture = async () => f.stream;
    await f.elements.get('record-voice').onclick();
    now = 1000;
    f.elements.get('finish-voice').onclick();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0][0].type, 'audio/webm');
    assert.equal(f.calls[0][1], 1);
    assert.equal(f.calls[0][2], 'recipient');
    assert.equal(globalThis.leases, 0);
    assert.equal(f.tracks[0].stopped, true);
  } finally {
    Object.defineProperty(globalThis, 'performance', { value: original, configurable: true });
  }
});
const emojiBundle = await build({
  entryPoints: ['MCTier-Linux-Web/web/builtinEmoji.ts'],
  bundle: true,
  format: 'esm',
  write: false,
});
const { builtinEmojiContent, builtinEmojiUrl, builtinEmojiItems } = await import(
  `data:text/javascript,${encodeURIComponent(emojiBundle.outputFiles[0].text)}`
);
test('builtin emoji follows the original versioned wire IDs and rejects unlisted assets', () => {
  assert.equal(builtinEmojiItems.length, 566);
  const content = builtinEmojiContent('builtin-1f600');
  assert.equal(content, 'mctier:emoji:v3:builtin-1f600');
  assert.equal(builtinEmojiUrl(content), '/builtin-emoji/1f600.gif');
  assert.equal(builtinEmojiUrl('mctier:emoji:v3:builtin-unknown'), null);
  assert.equal(builtinEmojiUrl('mctier:emoji:v3:builtin-../../secret'), null);
  assert.throws(() => builtinEmojiContent('builtin-unknown'));
});
