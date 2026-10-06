import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const output = await build({ entryPoints: ['MCTier-Linux-Web/frontend-src/services/version/VersionCheckService.ts'], bundle: true, format: 'esm', write: false, plugins: [{ name: 'version-source', setup(b) {
  b.onResolve({ filter: /platform\/localWeb$/ }, () => ({ path: 'local', namespace: 'fixture' }));
  b.onResolve({ filter: /^@tauri-apps\/api\/app$/ }, () => ({ path: 'app', namespace: 'fixture' }));
  b.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: args.path === 'app' ? 'export const getVersion=async()=>{throw Error("must compare the source baseline, not a manual reported version");};' : 'export const isLocalWeb=()=>true; export const localInvoke=(command)=>{globalThis.versionCommands.push(command);return Promise.resolve(globalThis.versionTags);};' }));
} }] });
const { versionCheckService } = await import(`data:text/javascript,${encodeURIComponent(output.outputFiles[0].text)}`);
test('Linux metadata bridge preserves upstream validation and semantic tag selection', async t => {
  globalThis.versionCommands = [];
  t.after(() => { delete globalThis.versionCommands; delete globalThis.versionTags; });
  globalThis.versionTags = JSON.stringify([{ name: 'v3.9.0', commit: { sha: 'a'.repeat(40) } }, { name: 'v3.12.0', commit: { sha: 'b'.repeat(40) } }, { name: 'v3.99.0.exe', commit: { sha: 'c'.repeat(40) } }]);
  assert.equal((await versionCheckService.fetchLatestVersion()).latestVersion, '3.12.0');
  assert.deepEqual(globalThis.versionCommands, ['get_upstream_version_tags']);
  globalThis.versionTags = JSON.stringify([{ name: 'v3.99.0', commit: { sha: 'invalid' } }]);
  assert.equal(await versionCheckService.fetchLatestVersion(), null);
});
