import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const output = await build({ stdin: { contents: "export * from './MCTier-Linux-Web/web/versionUpdater'; export * from './MCTier-Linux-Web/web/clientVersion';", resolveDir: process.cwd() }, bundle: true, format: 'esm', write: false, plugins: [{ name: 'version-fixture', setup(b) {
  b.onResolve({ filter: /version\/VersionCheckService$/ }, () => ({ path: 'fixture', namespace: 'fixture' }));
  b.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: 'export const versionCheckService={fetchLatestVersion:async()=>globalThis.versionFixture};' }));
} }] });
const module = await import(`data:text/javascript,${encodeURIComponent(output.outputFiles[0].text)}`);
test('latest upstream changes only the default; manual override and active session stay fixed', async t => {
  const prior = { localStorage: globalThis.localStorage, window: globalThis.window }, values = new Map();
  globalThis.localStorage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
  globalThis.window = { dispatchEvent() {} };
  t.after(() => { module.endReportedVersion(); Object.assign(globalThis, prior); delete globalThis.versionFixture; });
  module.beginReportedVersion();
  globalThis.versionFixture = { latestVersion: '3.11.0' };
  await module.refreshReportedDefault();
  assert.equal(module.defaultReportedVersion(), '3.11.0');
  assert.equal(module.reportedVersion(), '3.10.0');
  module.endReportedVersion(); assert.equal(module.reportedVersion(), '3.11.0');
  module.saveReportedVersion('3.9'); globalThis.versionFixture = { latestVersion: '3.12.0' }; await module.refreshReportedDefault();
  assert.equal(module.reportedVersion(), '3.9');
  module.resetReportedVersion(); assert.equal(module.reportedVersion(), '3.12.0');
  globalThis.versionFixture = { latestVersion: '3.8.0' }; await module.refreshReportedDefault(); assert.equal(module.defaultReportedVersion(), '3.12.0');
  globalThis.versionFixture = null; await assert.rejects(module.refreshReportedDefault(), /不切换来源/); assert.equal(module.defaultReportedVersion(), '3.12.0');
});
