// Keep encryption envelopes identical to the current desktop/Android invitation protocol.
import { readFile, writeFile } from 'node:fs/promises';
let source = await readFile(new URL('../../src-tauri/src/modules/secret_store.rs', import.meta.url), 'utf8');
const marker = 'crate::modules::lobby_manager::LobbyManager::validate_password(&plain)\n        .map_err(|e| e.to_string())?;';
if (!source.includes(marker)) throw new Error('Upstream secret validation changed; review Linux adapter.');
source = source.replaceAll('#[tauri::command]\n', '').replace(marker, 'crate::runtime::validate_password(&plain)?;');
await writeFile(new URL('../server/shared/secret_store.rs', import.meta.url), '// Generated from upstream secret_store.rs by prepare-secrets.mjs.\n' + source);
