import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
async function load(entry) {
  const bundle = await build({
    entryPoints: [entry],
    bundle: true,
    format: 'esm',
    write: false,
    plugins: [
      {
        name: 'mock-ipc',
        setup(b) {
          b.onResolve({ filter: /^@tauri-apps\/api\/core$/ }, () => ({
            path: 'ipc',
            namespace: 'mock',
          }));
          b.onLoad({ filter: /.*/, namespace: 'mock' }, () => ({
            contents:
              'export async function invoke(command,args){return globalThis.invokeFixture(command,args)}',
          }));
        },
      },
    ],
  });
  return import(
    `data:text/javascript,${encodeURIComponent(bundle.outputFiles[0].text)}#${Math.random()}`
  );
}
const data = await load('MCTier-Linux-Web/web/lobbyData.ts');
const invites = await load('MCTier-Linux-Web/frontend-src/services/lobby/lobbyInvite.ts');
const publicLobbies = await load('MCTier-Linux-Web/frontend-src/services/lobby/publicLobbies.ts');
const storage = () => {
  const map = new Map();
  return { getItem: (key) => map.get(key) || null, setItem: (key, value) => map.set(key, value) };
};
const invite = {
  name: 'Room123',
  password: 'Password123',
  serverNode: 'tcp://node.example:11010',
  signalingServer: 'wss://signal.example/signaling',
};
test('favorites store only protected passwords, update entries, preserve explicit addresses and cap recent history', async () => {
  globalThis.invokeFixture = async (command, args) => {
    assert.equal(command, 'protect_lobby_password');
    assert.equal(args.password, 'Password123');
    return 'mctier-local-v1:ZW5jcnlwdGVk';
  };
  const s = storage();
  await data.saveLobby(s, data.FAVORITES_KEY, invite, 'Local');
  await data.saveLobby(s, data.FAVORITES_KEY, invite, 'Updated');
  const saved = data.readSaved(s, data.FAVORITES_KEY);
  assert.equal(saved.length, 1);
  assert.equal(saved[0].playerName, 'Updated');
  assert.equal(saved[0].serverNode, invite.serverNode);
  assert.doesNotMatch(s.getItem(data.FAVORITES_KEY), /Password123/);
  for (let i = 0; i < 23; i++)
    await data.saveLobby(s, data.HISTORY_KEY, { ...invite, name: `Room${i}` }, 'Local', true);
  assert.equal(data.readSaved(s, data.HISTORY_KEY).length, 20);
  await assert.rejects(
    data.saveLobby(s, data.FAVORITES_KEY, { ...invite, serverNode: undefined }, 'Local'),
    /填写节点/
  );
});
test('favorite normalization rejects plaintext and unsafe addresses while retaining older records without addresses', () => {
  const base = { ...invite, id: 'fav', createdAt: 1, password: 'mctier-local-v1:ZW5jcnlwdGVk' };
  assert.equal(data.normalizeSaved({ ...base, password: 'secret123' }), null);
  assert.equal(data.normalizeSaved({ ...base, serverNode: 'file:///etc/passwd' }), null);
  assert.equal(data.normalizeSaved({ ...base, signalingServer: 'ws://insecure' }), null);
  assert.ok(data.normalizeSaved({ ...base, serverNode: undefined, signalingServer: undefined }));
  assert.equal(data.normalizeSaved({ ...base, useCount: Infinity }).useCount, 0);
});
test('v3 invitation generation and import use original command and protocol without plaintext', async () => {
  globalThis.invokeFixture = async (command, args) => {
    assert.equal(command, 'export_lobby_password');
    assert.equal(args.password, 'Password123');
    return 'mctier-invite-v3:ZW5jcnlwdGVk';
  };
  const text = await invites.formatLobbyInviteText(invite, 'zh');
  assert.doesNotMatch(text, /Password123/);
  const parsed = invites.parseLobbyInviteText(text);
  assert.deepEqual(parsed, { ...invite, password: 'mctier-invite-v3:ZW5jcnlwdGVk' });
  assert.equal(
    invites.parseLobbyInviteText('mctier://join?v=3&name=Room123&node=file:///etc/passwd'),
    null
  );
  assert.equal(invites.parseLobbyInviteText('mctier://join?v=999&name=Room123'), null);
});
test('public plaza sends only the original list query and does not invent missing nodes', async () => {
  const previousWindow = globalThis.window,
    previousSocket = globalThis.WebSocket;
  globalThis.window = { setTimeout, clearTimeout };
  let socket;
  globalThis.WebSocket = class {
    constructor(url) {
      socket = this;
      this.url = url;
      queueMicrotask(() => this.onopen());
    }
    send(data) {
      this.sent = JSON.parse(data);
      queueMicrotask(() =>
        this.onmessage({
          data: JSON.stringify({
            type: 'public-lobby-list-response',
            lobbies: [
              { lobbyName: 'Room123', hostName: 'Host', playerCount: 2 },
              {
                lobbyName: 'Other',
                hostName: 'Host',
                playerCount: 1,
                serverNode: 'tcp://node.example:11010',
              },
            ],
          }),
        })
      );
    }
    close() {
      this.closed = true;
    }
  };
  try {
    const rows = await publicLobbies.fetchPublicLobbies(invite.signalingServer);
    assert.deepEqual(socket.sent, { type: 'public-lobby-list-request' });
    assert.equal(socket.url, invite.signalingServer);
    assert.equal(socket.closed, true);
    assert.equal(rows[0].serverNode, undefined);
    assert.equal(rows[1].serverNode, invite.serverNode);
  } finally {
    globalThis.window = previousWindow;
    globalThis.WebSocket = previousSocket;
  }
});
test('public plaza cancels connections and fails promptly when closed before a response', async () => {
  const previousWindow = globalThis.window,
    previousSocket = globalThis.WebSocket;
  globalThis.window = { setTimeout, clearTimeout };
  let socket;
  globalThis.WebSocket = class {
    constructor() {
      socket = this;
    }
    close() {
      this.closed = true;
    }
  };
  try {
    const abort = new AbortController(),
      pending = publicLobbies.fetchPublicLobbies(invite.signalingServer, 500, abort.signal);
    abort.abort();
    await assert.rejects(pending, { name: 'AbortError' });
    assert.equal(socket.closed, true);
    const closed = publicLobbies.fetchPublicLobbies(invite.signalingServer, 500);
    socket.onclose();
    await assert.rejects(closed, /连接已关闭/);
    assert.equal(socket.closed, true);
    const oversized = publicLobbies.fetchPublicLobbies(invite.signalingServer, 500);
    socket.onmessage({ data: 'x'.repeat(512 * 1024 + 1) });
    await assert.rejects(oversized, /超过限制/);
    assert.equal(socket.closed, true);
  } finally {
    globalThis.window = previousWindow;
    globalThis.WebSocket = previousSocket;
  }
});
