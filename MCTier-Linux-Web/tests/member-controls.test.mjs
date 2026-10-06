import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const output = await build({ entryPoints: ['MCTier-Linux-Web/web/memberControls.ts'], bundle: true, format: 'esm', write: false, plugins: [{ name: 'controls-fixture', setup(b) {
  b.onResolve({ filter: /stores\/appStore$/ }, () => ({ path: 'store', namespace: 'fixture' }));
  b.onResolve({ filter: /webrtc\/WebRTCClient$/ }, () => ({ path: 'client', namespace: 'fixture' }));
  b.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: args.path === 'store' ? 'export const useAppStore={getState:()=>globalThis.memberFixture.store};' : 'export const webrtcClient={setPlayerMuted:(...args)=>globalThis.memberFixture.requests.push(["mute",...args]),transferHost:(...args)=>globalThis.memberFixture.requests.push(["transfer",...args]),kickPlayer:()=>true};' }));
} }] });
const { memberControls } = await import(`data:text/javascript,${encodeURIComponent(output.outputFiles[0].text)}`);
test('per-peer volume and mute stay local; host actions await authority and reject stale rooms', t => {
  const previous = { document: globalThis.document, window: globalThis.window };
  const requests = [], volumes = [], muted = new Set();
  globalThis.memberFixture = { requests, store: { getPlayerVolume: () => .5, isPlayerMuted: id => muted.has(id), togglePlayerMute: id => muted.has(id) ? muted.delete(id) : muted.add(id), setPlayerVolume: (...args) => volumes.push(args), hostMutedPlayers: new Set() } };
  globalThis.document = { createElement: tag => ({ tag, children: [], append(...children) { this.children.push(...children); }, setAttribute() {} }) };
  globalThis.window = { confirm: () => true };
  t.after(() => { Object.assign(globalThis, previous); delete globalThis.memberFixture; });
  let valid = true, host = true;
  const panel = memberControls({ id: 'peer', name: 'Phone' }, { valid: () => valid, host: () => host, status() {} });
  assert.equal(panel.tag, 'div', 'per-member volume must not be hidden in a disclosure');
  const slider = panel.children[0].children[0], actions = [panel.children[1].children[0], ...panel.children[2].children[1].children];
  slider.value = '25'; slider.oninput(); assert.deepEqual(volumes, [['peer', .25]]); assert.equal(requests.length, 0);
  actions[0].onclick(); assert.ok(muted.has('peer')); assert.equal(requests.length, 0);
  actions[1].onclick(); assert.deepEqual(requests[0], ['mute', 'peer', true]);
  assert.equal(globalThis.memberFixture.store.hostMutedPlayers.size, 0, 'request does not pretend server confirmation');
  actions[2].onclick(); assert.deepEqual(requests[1], ['transfer', 'peer']);
  host = false; actions[1].onclick(); assert.equal(requests.length, 2);
  valid = false; slider.oninput(); actions[2].onclick(); assert.equal(volumes.length, 1); assert.equal(requests.length, 2);
});
