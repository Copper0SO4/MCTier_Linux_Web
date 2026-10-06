import { applyUnifiedPatch } from './lib/frontend-patch.mjs';
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

for (const entry of (await readdir(here('../patches/frontend'))).filter(name => name.endsWith('.patch')).sort()) {
  const patch = await readFile(path.join(here('../patches/frontend'), entry), 'utf8');
  const match = /^\+\+\+ b\/(.+)$/m.exec(patch);
  if (!match) throw new Error(`Missing destination in Linux patch ${entry}`);
  if (path.isAbsolute(match[1]) || match[1].split('/').some(part => !part || part === '.' || part === '..') || /[\\\x00]/.test(match[1]))
    throw new Error(`Unsafe Linux patch destination in ${entry}`);
  const destination = path.join(generated, match[1]);
  let source = '';
  try { source = await readFile(destination, 'utf8'); } catch {}
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, applyUnifiedPatch(source, patch, match[1]));
}
const { version } = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
if (typeof version !== 'string' || !/^\d+\.\d+\.\d+$/.test(version))
  throw new Error('Invalid upstream package version; review compatibility metadata.');
await writeFile(path.join(generated, 'services/platform/linuxWebVersion.ts'),
  `// Generated from upstream package.json; Linux release number remains separate.\nexport const UPSTREAM_VERSION = ${JSON.stringify(version)};\n`);
console.log('Prepared current upstream src with Linux-only patches');
