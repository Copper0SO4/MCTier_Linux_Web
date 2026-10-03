import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const bundle = await build({
  entryPoints: ['MCTier-Linux-Web/web/featureAvailability.ts'],
  bundle: true,
  format: 'esm',
  write: false,
});
const { FEATURES, browserAvailability } = await import(
  `data:text/javascript,${encodeURIComponent(bundle.outputFiles[0].text)}`
);

test('Firefox room entry stays blocked while the reported 1006 problem is unresolved', () => {
  for (const ua of [
    'Mozilla/5.0 (X11; Linux x86_64; rv:157.0) Gecko/20100101 Firefox/157.0',
    'Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0',
  ]) {
    const result = browserAvailability(ua);
    assert.equal(result.canJoin, false);
    assert.match(result.warning, /1006/);
    assert.match(result.warning, /根因尚未确认/);
  }
  assert.equal(
    browserAvailability(
      'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/141.0.0.0 Safari/537.36'
    ).canJoin,
    true
  );
});

test('unimplemented native and file operations cannot be advertised as available', () => {
  const indexed = new Map(FEATURES.map((feature) => [feature.id, feature]));
  assert.equal(indexed.size, FEATURES.length);
  for (const id of [
    'folder-share',
    'remote-control',
    'system-audio',
    'room-tools',
    'magic-dns',
    'desktop-integration',
    'auto-lobby',
  ]) {
    assert.equal(indexed.get(id)?.state, 'blocked', `${id} must stay blocked until migrated`);
    assert.ok(indexed.get(id)?.reason.length > 10);
  }
  // Receiving attachments and viewing screens remain independent of blocked sending/control.
  for (const id of ['chat', 'screen', 'voice', 'network', 'send-file', 'send-image', 'record-voice'])
    assert.equal(indexed.get(id)?.state, 'experimental');
});
