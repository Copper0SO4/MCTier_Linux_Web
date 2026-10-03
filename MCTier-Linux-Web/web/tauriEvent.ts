/** Browser-local event bus. Signaling status comes from the original WebSocket client. */
export async function listen<T>(name: string, callback: (event: { event: string; id: number; payload: T }) => void): Promise<() => void> {
  const handler = (event: Event) => callback({ event: name, id: 0, payload: (event as CustomEvent<T>).detail });
  window.addEventListener(`mctier-local:${name}`, handler);
  return () => window.removeEventListener(`mctier-local:${name}`, handler);
}
export async function emit(name: string, payload?: unknown): Promise<void> {
  window.dispatchEvent(new CustomEvent(`mctier-local:${name}`, { detail: payload }));
}
export async function emitTo(): Promise<void> { throw new Error('浏览器客户端不支持独立桌面窗口'); }
