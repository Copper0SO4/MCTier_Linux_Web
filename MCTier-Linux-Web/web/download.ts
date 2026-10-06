import { boundedBlob } from './boundedResponse';
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
  return boundedBlob(response, MAX_CHAT_ATTACHMENT_BYTES, attachment.size);
}
