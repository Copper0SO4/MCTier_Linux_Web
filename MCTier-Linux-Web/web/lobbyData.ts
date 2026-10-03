import {
  isSafeServerNode,
  isSafeSignalingServer,
  sanitizeUntrustedText,
} from '../frontend-src/security/trustBoundary';
import { isProtectedPassword, protectLobbyPassword } from '../frontend-src/security/lobbyPassword';
import type { LobbyInvite } from '../frontend-src/services/lobby/lobbyInvite';
export interface SavedLobby extends LobbyInvite {
  id: string;
  playerName?: string;
  createdAt: number;
  lastUsedAt?: number;
  useCount: number;
}
export const FAVORITES_KEY = 'mctier_favorite_lobbies';
export const HISTORY_KEY = 'mctier_linux_web_recent_lobbies';
export function normalizeSaved(value: unknown): SavedLobby | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const v = value as Record<string, unknown>;
  const name = sanitizeUntrustedText(v.name, 32).trim(),
    id = sanitizeUntrustedText(v.id, 128).trim();
  if (!name || !id || (v.password && !isProtectedPassword(v.password))) return null;
  if (
    (v.serverNode !== undefined &&
      (typeof v.serverNode !== 'string' ||
        !isSafeServerNode(v.serverNode) ||
        v.serverNode === 'custom')) ||
    (v.signalingServer !== undefined &&
      (typeof v.signalingServer !== 'string' || !isSafeSignalingServer(v.signalingServer)))
  )
    return null;
  const number = (x: unknown, max: number) =>
    typeof x === 'number' && Number.isFinite(x) ? Math.max(0, Math.min(max, Math.trunc(x))) : 0;
  return {
    id,
    name,
    password: typeof v.password === 'string' ? v.password : '',
    playerName: sanitizeUntrustedText(v.playerName, 32),
    serverNode: typeof v.serverNode === 'string' ? v.serverNode : undefined,
    signalingServer: typeof v.signalingServer === 'string' ? v.signalingServer : undefined,
    createdAt: number(v.createdAt, Number.MAX_SAFE_INTEGER),
    lastUsedAt: number(v.lastUsedAt, Number.MAX_SAFE_INTEGER),
    useCount: number(v.useCount, 1_000_000),
  };
}
export function readSaved(storage: Storage, key: string): SavedLobby[] {
  try {
    const data = JSON.parse(storage.getItem(key) || '[]');
    return Array.isArray(data)
      ? data
          .slice(0, 100)
          .map(normalizeSaved)
          .filter((v): v is SavedLobby => !!v)
      : [];
  } catch {
    return [];
  }
}
export async function saveLobby(
  storage: Storage,
  key: string,
  invite: LobbyInvite,
  playerName: string,
  history = false
): Promise<void> {
  if (!invite.serverNode || !invite.signalingServer) throw new Error('收藏前请填写节点与信令地址');
  const password = await protectLobbyPassword(invite.password);
  const list = readSaved(storage, key),
    previous = list.find(
      (v) =>
        v.name === invite.name &&
        v.serverNode === invite.serverNode &&
        v.signalingServer === invite.signalingServer
    );
  const value = normalizeSaved({
    ...invite,
    password,
    playerName,
    id: previous?.id || `fav_${crypto.randomUUID()}`,
    createdAt: previous?.createdAt || Date.now(),
    lastUsedAt: Date.now(),
    useCount: (previous?.useCount || 0) + (history ? 1 : 0),
  });
  if (!value) throw new Error('大厅地址或密码保存格式无效');
  storage.setItem(
    key,
    JSON.stringify([value, ...list.filter((v) => v.id !== value.id)].slice(0, history ? 20 : 100))
  );
}
