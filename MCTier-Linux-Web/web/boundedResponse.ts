/** Collect binary bytes within a known budget, even when Content-Length is absent. */
export async function boundedBlob(response: Response, limit: number, expected?: number): Promise<Blob> {
  if (!Number.isSafeInteger(limit) || limit < 0 || (expected !== undefined && (!Number.isSafeInteger(expected) || expected < 0 || expected > limit))) {
    await response.body?.cancel().catch(() => {});
    throw new Error('文件大小无效或超出限制');
  }
  const length = response.headers.get('content-length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > limit || (expected !== undefined && Number(length) !== expected))) {
    await response.body?.cancel().catch(() => {});
    throw new Error('附件实际大小与消息元数据不一致或超出限制');
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
      if (size > limit || (expected !== undefined && size > expected)) throw new Error('附件实际大小超出限制');
      chunks.push(new Uint8Array(value));
    }
    if (expected !== undefined && size !== expected) throw new Error('附件实际大小与消息元数据不一致');
    return new Blob(chunks, { type: 'application/octet-stream' });
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

/** Each dialog owns every in-flight request, including concurrent downloads. */
export class TransferScope {
  private requests = new Set<AbortController>();
  begin(): AbortController { const controller = new AbortController(); this.requests.add(controller); return controller; }
  finish(controller: AbortController): void { this.requests.delete(controller); }
  cancel(): void { for (const request of this.requests) request.abort(); this.requests.clear(); }
}
