import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const load = async (entry, browser = true) => {
  const output = await build({ entryPoints: [entry], bundle: true, format: 'esm', write: false,
    define: { 'import.meta.env': JSON.stringify({ VITE_MCTIER_LOCAL_WEB: browser ? '1' : undefined }) },
    plugins: [{ name: 'deny-native-ipc', setup(builder) {
      builder.onResolve({ filter: /^@tauri-apps\/api\/core$/ }, () => ({ path: 'ipc', namespace: 'ipc' }));
      builder.onLoad({ filter: /.*/, namespace: 'ipc' }, () => ({ contents: 'export const invoke=()=>{throw new Error("Native IPC must not run in browser capture")};' }));
    } }],
  });
  return import(`data:text/javascript,${encodeURIComponent(output.outputFiles[0].text)}#${Math.random()}`);
};

test('browser attachment downloads authenticate POST and reject truncated or oversized bytes', async t => {
  const storage = new Map(); const previous = globalThis.sessionStorage;
  globalThis.sessionStorage = { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) };
  t.after(() => { globalThis.sessionStorage = previous; });
  const calls = []; let data = 'abc'; let status = 200;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push([url, options]);
    return url === '/api/bootstrap' ? new Response(JSON.stringify({ csrf: 'a'.repeat(64), defaults: {} })) : new Response(data, { status });
  });
  const { downloadAttachment } = await load('MCTier-Linux-Web/web/download.ts');
  const attachment = { id: 'attachment-123456', name: 'example.txt', mime: 'text/plain', size: 3 };
  const controller = new AbortController();
  assert.equal(await (await downloadAttachment('peer', attachment, controller.signal)).text(), 'abc');
  const [url, options] = calls.at(-1);
  assert.equal(url, '/api/chat/attachment'); assert.equal(options.method, 'POST');
  assert.equal(options.signal, controller.signal);
  assert.equal(options.headers['x-mctier-csrf'], 'a'.repeat(64));
  assert.match(options.headers['x-mctier-client'], /^[a-f0-9]{32}$/);
  assert.deepEqual(JSON.parse(options.body), { ownerPlayerId: 'peer', attachment });
  data = 'ab'; await assert.rejects(downloadAttachment('peer', attachment), /大小与消息/);
  data = 'abcd'; await assert.rejects(downloadAttachment('peer', attachment), /超出限制/);
  status = 403; data = JSON.stringify({ error: '附件发送者已离开大厅' });
  await assert.rejects(downloadAttachment('peer', attachment), /发送者已离开/);
});

test('browser transport boot is read-only and authenticated commands keep credentials out of URLs', async t => {
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push([url, options]);
    return new Response(JSON.stringify(url === '/api/bootstrap' ? { csrf: 'a'.repeat(64), defaults: {} } : { success: true }), { status: 200, headers: { 'content-type': 'application/json' } });
  });
  const storage = new Map();
  const previous = globalThis.sessionStorage;
  globalThis.sessionStorage = { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) };
  t.after(() => { globalThis.sessionStorage = previous; });
  const module = await load('MCTier-Linux-Web/frontend-src/services/platform/localWeb.ts');
  assert.equal(calls.length, 0);
  await module.localBootstrap();
  assert.equal(calls.length, 1);
  await module.localInvoke('connect_lobby', { password: 'private123' });
  assert.equal(calls.length, 2);
  assert.equal(calls[1][0], '/api/invoke');
  assert.equal(calls[1][1].headers['x-mctier-csrf'], 'a'.repeat(64));
  assert.match(calls[1][1].headers['x-mctier-client'], /^[a-f0-9]{32}$/);
  assert.equal(JSON.parse(calls[1][1].body).args.password, 'private123');
  assert.doesNotMatch(calls.map(call => call[0]).join(' '), /private|csrf|token=/);
  const desktop = await load('MCTier-Linux-Web/frontend-src/services/platform/localWeb.ts', false);
  assert.equal(desktop.isLocalWeb(), false);
});

test('browser capture uses its picker and releases a stream returned after cancellation', async t => {
  let resolve, options;
  const previous = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices');
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getDisplayMedia: async value => { options = value; return new Promise(r => { resolve = r; }); } } });
  t.after(() => { if (previous) Object.defineProperty(navigator, 'mediaDevices', previous); else delete navigator.mediaDevices; });
  const module = await load('MCTier-Linux-Web/frontend-src/services/screenShare/nativeCapture.ts');
  const controller = new AbortController();
  const pending = module.requestNativeScreen({ resolution: 720, frameRate: 30, bitrateMbps: 0 }, false, controller.signal);
  assert.equal(options.audio, false);
  assert.equal(options.video.height.ideal, 720);
  controller.abort();
  let stopped = false;
  resolve({ getTracks: () => [{ stop: () => { stopped = true; } }] });
  await assert.rejects(pending, { name: 'AbortError' });
  assert.equal(stopped, true);
  await assert.rejects(module.requestNativeScreen({}, true), /尚未实现/);
});

test('manual signaling probe receives v3 challenge without sending registration and closes its socket', async t => {
  const previous = globalThis.WebSocket;
  const sockets = [];
  class Socket {
    constructor(url) { this.url = url; this.sent = []; this.closed = false; sockets.push(this); }
    send(data) { this.sent.push(data); }
    close() { this.closed = true; }
  }
  globalThis.WebSocket = Socket;
  t.after(() => { globalThis.WebSocket = previous; });
  const module = await load('MCTier-Linux-Web/web/signalingProbe.ts');
  const pending = module.probeSignaling('wss://mctier.pmhs.top/signaling');
  sockets[0].onopen();
  sockets[0].onmessage({ data: JSON.stringify({ type: 'server-challenge', protocolVersion: 3, lobbyEntryModes: true, challenge: 'a'.repeat(64) }) });
  assert.match(await pending, /未发送注册/);
  assert.deepEqual(sockets[0].sent, []);
  assert.equal(sockets[0].closed, true);
  assert.equal(sockets[0].onmessage, null);
  const failed = module.probeSignaling('wss://mctier.pmhs.top/signaling');
  sockets[1].onerror();
  await assert.rejects(failed, /浏览器无法建立 WebSocket/);
  assert.equal(sockets[1].closed, true);
  const timeout = module.probeSignaling('wss://mctier.pmhs.top/signaling', 5);
  await assert.rejects(timeout, /超时/);
  assert.equal(sockets[2].closed, true);
  const malformed = module.probeSignaling('wss://mctier.pmhs.top/signaling');
  sockets[3].onmessage({ data: '{broken' });
  await assert.rejects(malformed, /JSON/);
  assert.equal(sockets[3].closed, true);
  await assert.rejects(module.probeSignaling('ws://mctier.pmhs.top/signaling'), /有效的 WSS/);
  assert.equal(sockets.length, 4);
});
