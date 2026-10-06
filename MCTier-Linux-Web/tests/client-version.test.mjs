import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import path from 'node:path';
const result = await build({ entryPoints: ['MCTier-Linux-Web/web/clientVersion.ts'], bundle: true, format: 'esm', write: false });
const version = await import(`data:text/javascript,${encodeURIComponent(result.outputFiles[0].text)}`);
test('reported version defaults to upstream and stays fixed across session reconnects', t => {
  const prior = globalThis.localStorage;
  const values = new Map();
  globalThis.localStorage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
  t.after(() => { version.endReportedVersion(); globalThis.localStorage = prior; });
  assert.equal(version.reportedVersion(), '3.10.0');
  version.saveReportedVersion(' 3.10 ');
  assert.equal(version.beginReportedVersion(), '3.10');
  version.saveReportedVersion('3.11.0');
  assert.equal(version.reportedVersion(), '3.10');
  version.endReportedVersion();
  assert.equal(version.beginReportedVersion(), '3.11.0');
  version.resetReportedVersion();
  assert.equal(version.reportedVersion(), '3.11.0');
  version.endReportedVersion();
  assert.equal(version.reportedVersion(), '3.10.0');
  for (const value of ['', 'v3.10', '3.10.0\n<script>', '3.10.0-beta', '3.10.0.1'])
    assert.throws(() => version.saveReportedVersion(value), /数字版本/);
});
test('blocked or corrupt preference storage still allows the default version', t => {
  const prior = globalThis.localStorage;
  t.after(() => { globalThis.localStorage = prior; });
  globalThis.localStorage = { getItem: () => 'invalid' };
  assert.equal(version.savedReportedVersion(), '3.10.0');
  globalThis.localStorage = { getItem: () => { throw Error('blocked'); } };
  assert.equal(version.beginReportedVersion(), '3.10.0');
  version.endReportedVersion();
});

test('upstream version cache refreshes at the next Linux entry, while reconnect uses session version', async t => {
  const prior = globalThis.localStorage;
  const values = new Map();
  globalThis.localStorage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) };
  t.after(() => { globalThis.localStorage = prior; });
  const output = await build({ stdin: {contents: "export * from './MCTier-Linux-Web/frontend-src/services/version/appVersion.ts'; export * from './MCTier-Linux-Web/web/clientVersion.ts';", resolveDir: process.cwd()}, bundle: true, format: 'esm', write: false,
    plugins: [{ name: 'browser-platform', setup(b) {
      b.onResolve({filter: /^@tauri-apps\/api\/app$/}, () => ({path: path.resolve('MCTier-Linux-Web/web/tauriApp.ts'), external: false}));
      b.onResolve({filter: /platform\/localWeb$/}, () => ({path: 'local', namespace: 'fixture'}));
      b.onLoad({filter: /.*/, namespace: 'fixture'}, () => ({contents: 'export const isLocalWeb=()=>true;'}));
    }}],
  });
  const module = await import(`data:text/javascript,${encodeURIComponent(output.outputFiles[0].text)}`);
  module.beginReportedVersion();
  assert.equal(await module.loadAppVersion(), '3.10.0');
  values.set('mctier-linux-web-reported-version-v1','3.11.0');
  assert.equal(await module.loadAppVersion(), '3.10.0');
  module.endReportedVersion();
  module.beginReportedVersion();
  assert.equal(await module.loadAppVersion(), '3.11.0');
  assert.equal(module.appVersion(), '3.11.0');
});
