import { useAppStore } from '../frontend-src/stores/appStore';
import { webrtcClient } from '../frontend-src/services/webrtc/WebRTCClient';
import type { Player } from '../frontend-src/types';
type Context = { valid: () => boolean; host: () => boolean; status: (text: string, error?: boolean) => void };
export function memberControls(player: Player, ctx: Context): HTMLElement {
  const panel = document.createElement('details'); panel.className = 'member-controls';
  const summary = document.createElement('summary'); summary.textContent = '音量 / 成员操作'; panel.append(summary);
  const label = document.createElement('label'); label.textContent = '本地音量';
  const slider = document.createElement('input'); slider.type = 'range'; slider.min = '0'; slider.max = '100'; slider.step = '1';
  slider.setAttribute('aria-label', `${player.name}的本地音量`);
  slider.value = String(Math.round(useAppStore.getState().getPlayerVolume(player.id) * 100));
  const value = document.createElement('output'); value.textContent = `${slider.value}%`;
  slider.oninput = () => {
    if (!ctx.valid()) return;
    const volume = Number(slider.value); if (!Number.isFinite(volume)) return;
    useAppStore.getState().setPlayerVolume(player.id, Math.max(0, Math.min(100, volume)) / 100);
    value.textContent = `${slider.value}%`;
  };
  label.append(slider, value); panel.append(label);
  const actions = document.createElement('div'); actions.className = 'member-actions';
  function action(title: string, run: () => void) {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = title;
    button.onclick = () => { if (ctx.valid()) run(); }; actions.append(button); return button;
  }
  const mute = action(useAppStore.getState().isPlayerMuted(player.id) ? '恢复本地声音' : '仅本地静音', () => {
    useAppStore.getState().togglePlayerMute(player.id);
    mute.textContent = useAppStore.getState().isPlayerMuted(player.id) ? '恢复本地声音' : '仅本地静音';
  });
  if (ctx.host()) {
    action(useAppStore.getState().hostMutedPlayers.has(player.id) ? '解除语音禁言' : '语音禁言', () => {
      if (!ctx.host()) return;
      const muted = !useAppStore.getState().hostMutedPlayers.has(player.id);
      ctx.status(webrtcClient.setPlayerMuted(player.id, muted) ? '语音禁言请求已发送，等待信令确认。' : '禁言请求未发送，请检查信令。');
    });
    action('转让房主', () => {
      if (!ctx.host() || !window.confirm(`将房主转让给 ${player.name}？确认后你将失去房主管理权限。`)) return;
      if (!ctx.valid() || !ctx.host()) return;
      ctx.status(webrtcClient.transferHost(player.id) ? '房主转让请求已发送，等待信令确认。' : '转让请求未发送，请检查信令。');
    });
    action('移出大厅', () => {
      if (!ctx.host() || !window.confirm(`确认将 ${player.name} 移出大厅？`)) return;
      if (!ctx.valid() || !ctx.host()) return;
      ctx.status(webrtcClient.kickPlayer(player.id) ? '移出请求已发送。' : '移出请求未发送，请检查信令。');
    });
  }
  panel.append(actions); return panel;
}
