import { localInvoke } from '../frontend-src/services/platform/localWeb';
export const invoke = localInvoke;
export const isTauri = () => false;
export function convertFileSrc(): string { throw new Error('浏览器客户端不允许访问任意本机文件路径'); }
export class Channel<T> {
  onmessage: (message: T) => void = () => {};
  constructor() { throw new Error('浏览器客户端尚不支持原生 IPC channel'); }
}
