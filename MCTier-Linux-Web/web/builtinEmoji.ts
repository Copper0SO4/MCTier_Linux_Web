import manifest from '../../shared/builtin-emoji/manifest.json';
import {
  encodeBuiltinEmoji,
  decodeBuiltinEmoji,
} from '../frontend-src/services/emoji/builtinEmojiMessage';
const ids = new Set(manifest.ids.map((id) => `builtin-${id}`));
export const builtinEmojiItems = manifest.ids.map((id) => ({
  id: `builtin-${id}`,
  url: `/builtin-emoji/${id}.gif`,
  name: String.fromCodePoint(...id.split('-').map((part) => Number.parseInt(part, 16))),
}));
export function builtinEmojiContent(id: string): string {
  if (!ids.has(id)) throw new Error('内置表情未收录');
  return encodeBuiltinEmoji(id);
}
export function builtinEmojiUrl(content: string): string | null {
  const id = decodeBuiltinEmoji(content);
  return id && ids.has(id) ? `/builtin-emoji/${id.slice('builtin-'.length)}.gif` : null;
}
