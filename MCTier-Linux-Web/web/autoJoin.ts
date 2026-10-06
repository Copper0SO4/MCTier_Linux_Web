import { localInvoke } from '../frontend-src/services/platform/localWeb';
import type { NetworkSettings } from './networkPanel';
export type StartupLobby = { name: string; password: string; playerName: string; serverNode: string; signalingServer: string; networkSettings: NetworkSettings };
export type AutoNetwork = { input: StartupLobby; virtualIp: string; playerId: string };
export function setupAutoJoin(ctx: { snapshot: () => StartupLobby; status: (text: string, error?: boolean) => void }) {
  const container = document.getElementById('auto-join-settings')!;
  const title = document.createElement('h3'); title.textContent = '服务启动时自动加入组网';
  const hint = document.createElement('p'); hint.className = 'hint';
  hint.textContent = '仅在你开启并保存后生效。服务启动连接指定 EasyTier 房间；打开本网页后接续大厅信令：存在则加入，不存在则创建；密码错误停止组网并提示。不会自动开启麦克风/共享屏幕或申请系统授权。';
  const label = document.createElement('label'), enabled = document.createElement('input');
  label.className = 'network-check'; enabled.type = 'checkbox'; label.append(enabled, '启用自动加入');
  const grid = document.createElement('div'); grid.className = 'network-grid';
  const fields = new Map<keyof StartupLobby, HTMLInputElement>();
  for (const [key, title, type] of [
    ['name', '大厅名称', 'text'], ['playerName', '昵称', 'text'], ['password', '大厅密码（可留空）', 'password'],
    ['serverNode', 'EasyTier 节点', 'text'], ['signalingServer', 'MCTier 信令服务', 'text'],
  ] as const) {
    const label = document.createElement('label'), input = document.createElement('input');
    label.textContent = title; input.type = type; input.autocomplete = 'off'; input.spellcheck = false;
    input.maxLength = key === 'password' ? 4096 : key.endsWith('Server') || key === 'serverNode' ? 512 : 32;
    label.append(input); grid.append(label); fields.set(key, input);
  }
  const state = document.createElement('p'); state.className = 'hint'; state.setAttribute('role', 'status');
  const actions = document.createElement('div'); actions.className = 'settings-actions';
  const fill = (l: StartupLobby) => { for (const [key, input] of fields) input.value = String(l[key]); };
  const useForm = document.createElement('button'); useForm.type = 'button'; useForm.textContent = '使用当前大厅表单';
  useForm.onclick = () => fill(ctx.snapshot());
  const save = document.createElement('button'); save.type = 'button'; save.textContent = '保存启动配置';
  save.onclick = () => { void (async () => {
    save.disabled = true;
    try {
      const lobby = ctx.snapshot(); for (const [key, input] of fields) (lobby as unknown as Record<string, unknown>)[key] = key === 'password' ? input.value : input.value.trim();
      await localInvoke('save_auto_join_config', { enabled: enabled.checked, lobby: enabled.checked ? lobby : null });
      state.textContent = enabled.checked ? '已保存；下次启动服务自动组网。高级参数使用当前已保存的 EasyTier 设置。' : '已关闭并删除保存的启动目标；当前房间不受影响。';
      ctx.status(state.textContent); if (!enabled.checked) fields.get('password')!.value = '';
    } catch (error) { state.textContent = error instanceof Error ? error.message : String(error); ctx.status(state.textContent, true); }
    finally { save.disabled = false; }
  })(); };
  actions.append(useForm, save);
  container.append(title, hint, label, grid, actions, state);
  return { async load() {
    try {
      const c = await localInvoke<{ enabled: boolean; lobby: StartupLobby | null }>('get_auto_join_config');
      enabled.checked = c.enabled; fill(c.lobby ?? ctx.snapshot());
      state.textContent = c.enabled ? '自动加入已开启；密码以本机加密形式保存。' : '自动加入未开启。';
    } catch (error) { state.textContent = `无法读取启动配置：${error instanceof Error ? error.message : String(error)}`; }
  } };
}
export function showAutoJoinNotification(name: string) {
  const dialog = document.createElement('dialog'); dialog.className = 'auto-join-notification';
  const heading = document.createElement('h2'); heading.textContent = '已在房间内';
  const text = document.createElement('p'); text.textContent = `已接续大厅“${name}”。EasyTier 虚拟接口和大厅信令就绪；对端数据、语音及画面需分别验证。`;
  const close = document.createElement('button'); close.type = 'button'; close.textContent = '知道了'; close.onclick = () => dialog.close();
  dialog.append(heading, text, close); document.body.append(dialog); dialog.onclose = () => dialog.remove(); dialog.showModal(); close.focus();
}

export function showAutoJoinFailure(reason: string) {
  const dialog = document.createElement('dialog'); dialog.className = 'auto-join-notification';
  const heading = document.createElement('h2'); heading.textContent = '自动加入失败';
  const text = document.createElement('p'); text.textContent = `${reason}。服务器已拒绝注册，EasyTier 自动组网已停止；请在软件设置中修正大厅名称或密码。`;
  const close = document.createElement('button'); close.type = 'button'; close.textContent = '知道了'; close.onclick = () => dialog.close();
  dialog.append(heading, text, close); document.body.append(dialog); dialog.onclose = () => dialog.remove(); dialog.showModal(); close.focus();
}
