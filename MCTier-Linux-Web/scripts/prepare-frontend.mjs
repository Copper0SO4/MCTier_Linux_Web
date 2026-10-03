// Build-only overlay: always copy the current upstream tree, then apply small Linux patches.
import { cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = relative => fileURLToPath(new URL(relative, import.meta.url));
const root = here('../../');
const generated = here('../frontend-src');
await rm(generated, { recursive: true, force: true });
await mkdir(generated, { recursive: true });
await cp(path.join(root, 'src'), generated, { recursive: true });

function applyUnifiedPatch(source, patch, label) {
  const lines = source.split('\n');
  const records = patch.split('\n');
  let at = 0;
  while (at < records.length && !records[at].startsWith('@@ ')) at++;
  while (at < records.length) {
    const header = records[at++];
    const match = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(header);
    if (!match) throw new Error(`Invalid patch header for ${label}: ${header}`);
    const expected = Number(match[1]) - 1;
    const before = [], after = [];
    while (at < records.length && !records[at].startsWith('@@ ')) {
      const row = records[at++];
      if (row.startsWith('\\')) continue;
      if (row.startsWith(' ')) { before.push(row.slice(1)); after.push(row.slice(1)); }
      else if (row.startsWith('-')) before.push(row.slice(1));
      else if (row.startsWith('+')) after.push(row.slice(1));
      else if (row !== '') throw new Error(`Invalid patch line for ${label}`);
    }
    const candidates = [];
    for (let i = 0; i <= lines.length - before.length; i++) {
      if (before.every((line, j) => lines[i + j] === line)) candidates.push(i);
    }
    candidates.sort((a, b) => Math.abs(a - expected) - Math.abs(b - expected));
    const pos = candidates[0];
    if (pos === undefined) throw new Error(`Linux patch no longer applies to upstream ${label}; resolve this file's patch before building.`);
    lines.splice(pos, before.length, ...after);
  }
  return lines.join('\n');
}
for (const entry of (await readdir(here('../patches/frontend'))).filter(name => name.endsWith('.patch')).sort()) {
  const patch = await readFile(path.join(here('../patches/frontend'), entry), 'utf8');
  const match = /^\+\+\+ b\/(.+)$/m.exec(patch);
  if (!match) throw new Error(`Missing destination in Linux patch ${entry}`);
  const destination = path.join(generated, match[1]);
  let source = '';
  try { source = await readFile(destination, 'utf8'); } catch {}
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, applyUnifiedPatch(source, patch, match[1]));
}
console.log('Prepared current upstream src with Linux-only patches');
