import { localHeaders } from '../frontend-src/services/platform/localWeb';
import { MAX_CHAT_ATTACHMENT_BYTES, type ChatAttachment } from '../frontend-src/services/chat/fileAttachment';

export async function downloadAttachment(ownerPlayerId: string, attachment: ChatAttachment, signal?: AbortSignal): Promise<Blob> {
  const response = await fetch('/api/chat/attachment', {
    method: 'POST', credentials: 'same-origin', cache: 'no-store', signal,
    headers: { 'Content-Type': 'application/json', ...await localHeaders() },
    body: JSON.stringify({ ownerPlayerId, attachment }),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.error || `附件下载失败：HTTP ${response.status}`);
  }
  const reader = response.body?.getReader();
  if (!reader) throw new Error('浏览器未提供附件数据流');
  const chunks: Uint8Array<ArrayBuffer>[] = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > attachment.size || size > MAX_CHAT_ATTACHMENT_BYTES) throw new Error('附件实际大小超出限制');
      chunks.push(new Uint8Array(value));
    }
    if (size !== attachment.size) throw new Error('附件实际大小与消息元数据不一致');
    return new Blob(chunks, { type: 'application/octet-stream' });
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
