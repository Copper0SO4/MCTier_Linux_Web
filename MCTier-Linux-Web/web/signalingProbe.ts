import { isSafeSignalingServer } from '../frontend-src/security/trustBoundary';

/** User-initiated transport check: receives a challenge, sends no registration. */
export function probeSignaling(address: string, timeoutMs = 8000): Promise<string> {
  if (!isSafeSignalingServer(address)) return Promise.reject(new Error('请填写有效的 WSS 信令地址'));
  return new Promise((resolve, reject) => {
    let socket: WebSocket | undefined;
    let opened = false, settled = false;
    const finish = (error?: string) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (socket) {
        socket.onopen = socket.onmessage = socket.onerror = socket.onclose = null;
        socket.close();
      }
      if (error) reject(new Error(error));
      else resolve('WebSocket 已连通，收到协议 v3 challenge。未发送注册、房间名或密码；这不代表房间、组网或媒体已通过。');
    };
    const timer = setTimeout(() => finish(opened ? 'WebSocket 已打开，但未及时收到协议 v3 challenge' : '建立 WebSocket 超时；请查看 Firefox 网络面板及代理/TLS 错误'), timeoutMs);
    try {
      socket = new WebSocket(address);
      socket.onopen = () => { opened = true; };
      socket.onmessage = event => {
        try {
          if (typeof event.data !== 'string' || event.data.length > 4096) return finish('信令握手响应无效');
          const frame = JSON.parse(event.data);
          if (frame.type !== 'server-challenge' || frame.protocolVersion !== 3 || !/^[a-f0-9]{64}$/.test(frame.challenge)) return finish('信令未返回预期的协议 v3 challenge');
          finish();
        } catch { finish('信令握手响应不是有效 JSON'); }
      };
      socket.onerror = () => finish(opened ? '信令连接在等待 challenge 时失败' : '浏览器无法建立 WebSocket。请查看 Firefox 控制台/网络面板的 CSP、代理、DNS 或 TLS 错误；浏览器 API 不提供具体 HTTP 状态码');
      socket.onclose = event => finish(`握手完成前断开，关闭码 ${event.code}`);
    } catch (error) { finish(error instanceof Error ? error.message : String(error)); }
  });
}
