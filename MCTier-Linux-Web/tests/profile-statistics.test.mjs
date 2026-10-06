import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const output = await build({ stdin: { contents: "export * from './MCTier-Linux-Web/web/profile'; export * from './MCTier-Linux-Web/web/sessionStatistics';", resolveDir: process.cwd() }, bundle: true, format: 'esm', write: false });
const module = await import(`data:text/javascript,${encodeURIComponent(output.outputFiles[0].text)}`);
test('profile storage validates nickname and bounded safe avatar, preserves old value on rejection', t => {
  const prior = globalThis.localStorage, values = new Map();
  globalThis.localStorage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) };
  t.after(() => { globalThis.localStorage = prior; });
  const avatarData = 'data:image/jpeg;base64,/9j/';
  assert.deepEqual(module.writeProfile({ name: ' Linux ', avatarData }), { name: 'Linux', avatarData });
  for (const value of [{ name: '' }, { name: 'a'.repeat(33) }, { name: 'hi\n' + 'bad' }, { name: 'Linux', avatarData: 'data:image/svg+xml;base64,AAAA' }, { name: 'Linux', avatarData: 'data:image/png;base64,' + 'A'.repeat(170000) }]) assert.throws(() => module.writeProfile(value));
  assert.equal(module.readProfile().avatarData, avatarData);
  globalThis.localStorage.getItem = () => { throw Error('blocked'); };
  assert.deepEqual(module.readProfile(), { name: '' });
});
test('avatar rejects non-image and oversized input before decoding', async () => {
  await assert.rejects(module.compressAvatar({ type: 'image/svg+xml', size: 1 }), /PNG/);
  await assert.rejects(module.compressAvatar({ type: 'image/png', size: 9 * 1024 * 1024 }), /8 MiB/);
});
test('statistics distinguish page records and voice RTP from total network traffic', () => {
  const rows = new Map(module.statisticsRows({ online: true, startedAt: 1000, members: 2, sent: 3, received: 4, failed: 1, sentBytes: 1024, receivedBytes: 2048, mediaPeers: 1, reconnects: 2 }, 62000));
  assert.equal(rows.get('本次在线时长'), '1 分 1 秒');
  assert.equal(rows.get('当前语音连接发送 RTP'), '1.0 KiB');
  assert.equal(rows.get('信令重连次数'), '2');
});
