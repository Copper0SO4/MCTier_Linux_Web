// Manual adapters must be re-reviewed when their official source changes.
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const root = new URL('../../', import.meta.url);
const manifest = JSON.parse(await readFile(new URL('../upstream-sources.json', import.meta.url), 'utf8'));
const { version } = JSON.parse(await readFile(new URL('package.json', root), 'utf8'));
const errors = [];
if (version !== manifest.upstreamVersion) errors.push(`Upstream version ${version} differs from reviewed ${manifest.upstreamVersion}`);
for (const item of manifest.manualAdapters) {
  const hash = createHash('sha256').update(await readFile(new URL(item.source, root))).digest('hex');
  if (hash !== item.sourceSha256) errors.push(`Review ${item.adapter} against changed ${item.source}`);
}
if (errors.length) throw new Error(`${errors.join('\n')}\nReview protocol/config changes and update upstream-sources.json deliberately; do not auto-accept hashes.`);
console.log(`Checked ${manifest.manualAdapters.length} manual Rust adapter sources against reviewed upstream ${version}`);
