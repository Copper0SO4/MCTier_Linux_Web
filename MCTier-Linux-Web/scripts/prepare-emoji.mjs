// Unpack the current upstream's authenticated v3 pack into generated web assets.
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
const root = new URL('../../', import.meta.url);
const manifest = JSON.parse(
  await readFile(new URL('shared/builtin-emoji/manifest.json', root), 'utf8')
);
// Upstream dbb7bbd's tracked pack differs from its manifest sha256 field.
// Pin the actual tracked bytes; fail on any new pack until reviewed.
const packSha256 = '2f538e09f787ab7dfac69120be1a7ddf42ff38c4d8521a62aa445a5dd91882f6';
const packed = await readFile(new URL('shared/builtin-emoji/builtin-v3.pack.gz', root));
if (manifest.version !== 3 || createHash('sha256').update(packed).digest('hex') !== packSha256)
  throw new Error('Official emoji pack hash/version mismatch');
const data = gunzipSync(packed, { maxOutputLength: 512 * 1024 * 1024 });
const magic = Buffer.from('MCTIER_EMOJI_PACK_V3\0');
if (!data.subarray(0, magic.length).equals(magic)) throw new Error('Emoji pack magic mismatch');
let at = magic.length;
const count = data.readUInt32LE(at);
at += 4;
if (count !== manifest.count || count < 100 || count > 2000)
  throw new Error('Emoji count mismatch');
const entries = new Map();
for (let i = 0; i < count; i++) {
  const idLength = data.readUInt16LE(at),
    size = data.readUInt32LE(at + 2);
  at += 6;
  if (
    !idLength ||
    idLength > 128 ||
    size < 6 ||
    size > 4 * 1024 * 1024 ||
    at + idLength + size > data.length
  )
    throw new Error('Emoji item bounds');
  const id = data.subarray(at, at + idLength).toString('utf8');
  at += idLength;
  const gif = data.subarray(at, at + size);
  at += size;
  if (
    !/^[a-f0-9]+(?:-[a-f0-9]+)*$/.test(id) ||
    entries.has(id) ||
    !manifest.ids.includes(id) ||
    !['GIF87a', 'GIF89a'].includes(gif.subarray(0, 6).toString())
  )
    throw new Error('Invalid emoji ID/GIF');
  entries.set(id, gif);
}
if (at !== data.length || entries.size !== manifest.ids.length)
  throw new Error('Emoji pack trailing data/index mismatch');
const output = new URL('MCTier-Linux-Web/web-dist/builtin-emoji/', root);
await mkdir(output, { recursive: true });
for (const [id, gif] of entries) await writeFile(new URL(`${id}.gif`, output), gif);
console.log(`Embedded ${count} official v3 animated emoji assets`);
