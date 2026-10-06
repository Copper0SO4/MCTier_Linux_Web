/** Discard asynchronous results belonging to a room that has since changed. */
export function captureSession<T>(current: () => T): () => boolean {
  const captured = current();
  return () => Object.is(captured, current());
}
