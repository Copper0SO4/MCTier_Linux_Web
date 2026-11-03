import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const here = (relative) => fileURLToPath(new URL(relative, import.meta.url));

const CORE = 'f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322';
const CLI = 'e339aea31943f0c5ced2a5a6ecdd675da3bb25843cf847107744e656f8200838';

test('pinned EasyTier hashes stay identical across the server and packaging scripts', () => {
  const both = [
    'MCTier-Linux-Web/server/src/runtime.rs',
    'MCTier-Linux-Web/scripts/fetch-binaries.sh',
    'MCTier-Linux-Web/scripts/package-deb.sh',
    'MCTier-Linux-Web/scripts/package-portable.sh',
    'MCTier-Linux-Web/scripts/package-release.sh',
  ];
  const coreOnly = [
    'MCTier-Linux-Web/scripts/launch-linux-web.sh',
    'MCTier-Linux-Web/packaging/README-Linux.txt',
  ];
  for (const file of both) {
    const text = readFileSync(here(`../../${file}`), 'utf8').toLowerCase();
    assert.ok(text.includes(CORE), `${file} is missing the EasyTier core hash`);
    assert.ok(text.includes(CLI), `${file} is missing the EasyTier CLI hash`);
  }
  for (const file of coreOnly) {
    const text = readFileSync(here(`../../${file}`), 'utf8').toLowerCase();
    assert.ok(text.includes(CORE), `${file} is missing the EasyTier core hash`);
  }
});
