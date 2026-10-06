import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const output = await build({ entryPoints: ['MCTier-Linux-Web/web/sessionGuard.ts'], bundle: true, format: 'esm', write: false });
const { captureSession } = await import(`data:text/javascript,${encodeURIComponent(output.outputFiles[0].text)}`);
test('late diagnostics cannot stop or update a replacement room, including idle-to-join', async () => {
  let ticket = null, resolve;
  const valid = captureSession(() => ticket);
  let stopped = false;
  const pending = new Promise(r => { resolve = r; }).then(status => { if (valid() && !status.session) stopped = true; });
  ticket = { room: 'new' }; resolve({ session: null }); await pending;
  assert.equal(stopped, false);
  const room = ticket, oldRoom = captureSession(() => ticket);
  assert.equal(oldRoom(), true); ticket = null; assert.equal(oldRoom(), false);
  ticket = { ...room }; assert.equal(oldRoom(), false, 'same name is not the same instance');
});
