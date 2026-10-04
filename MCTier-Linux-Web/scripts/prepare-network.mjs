// Pure upstream Minecraft status discovery; only remove Tauri command attributes.
import { readFile, writeFile } from 'node:fs/promises';
const source = await readFile(new URL('../../src-tauri/src/modules/minecraft_discovery.rs', import.meta.url), 'utf8');
if (!source.includes('pub async fn scan_minecraft_servers(') || !source.includes('pub fn is_allowed_mc_target('))
  throw new Error('Upstream Minecraft discovery changed; review Linux adapter.');
await writeFile(new URL('../server/shared/minecraft_discovery.rs', import.meta.url), '// Generated from upstream minecraft_discovery.rs by prepare-network.mjs.\n' + source.replaceAll('#[tauri::command]\n', ''));
