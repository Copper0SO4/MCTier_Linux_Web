import { isSafeImageDataUrl } from '../frontend-src/security/trustBoundary';
export type Profile = { name: string; avatarData?: string };
const KEY = 'mctier-linux-web-profile-v1';
const MAX_AVATAR = Math.floor(120_000 * 1.37);
export function validateProfile(value: Profile): Profile {
  const name = value.name.trim();
  if (!name || name.length > 32 || /[\u0000-\u001f\u007f]/.test(name)) throw new Error('昵称需为 1–32 字且不包含控制字符');
  if (value.avatarData !== undefined && (!isSafeImageDataUrl(value.avatarData) || value.avatarData.length > MAX_AVATAR))
    throw new Error('头像格式或大小无效');
  return { name, avatarData: value.avatarData };
}
export function readProfile(): Profile {
  try { return validateProfile(JSON.parse(localStorage.getItem(KEY) || '{}')); }
  catch { return { name: '' }; }
}
export function writeProfile(value: Profile): Profile {
  const profile = validateProfile(value);
  localStorage.setItem(KEY, JSON.stringify(profile));
  return profile;
}
export async function compressAvatar(file: File): Promise<string> {
  if (!['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(file.type) || file.size > 8 * 1024 * 1024)
    throw new Error('请选择不超过 8 MiB 的 PNG/JPEG/WebP/GIF 图片');
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve(); image.onerror = () => reject(new Error('头像无法解码')); image.src = url;
    });
    if (!image.naturalWidth || !image.naturalHeight || image.naturalWidth * image.naturalHeight > 16_000_000)
      throw new Error('头像分辨率过大或无效');
    // Same output dimensions/quality/budget as the upstream Avatar picker.
    const scale = Math.min(1, 256 / Math.max(image.naturalWidth, image.naturalHeight));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
    const context = canvas.getContext('2d');
    if (!context) throw new Error('浏览器不支持头像处理');
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    let quality = 0.82, data = canvas.toDataURL('image/jpeg', quality);
    while (data.length > MAX_AVATAR && quality > 0.42) { quality -= 0.08; data = canvas.toDataURL('image/jpeg', quality); }
    if (data.length > MAX_AVATAR) throw new Error('头像压缩后仍过大，请选择更小的图片');
    return data;
  } finally { URL.revokeObjectURL(url); }
}
type Context = {
  online: () => boolean;
  name: () => string;
  identity: () => string;
  ip: () => string;
  apply: (profile: Profile) => Promise<void>;
  status: (text: string, error?: boolean) => void;
};
export function setupProfile(ctx: Context) {
  const container = document.getElementById('profile-panel')!;
  const make = <K extends keyof HTMLElementTagNameMap>(tag: K, text?: string) => {
    const element = document.createElement(tag); if (text) element.textContent = text; return element;
  };
  const avatar = make('img'); avatar.className = 'profile-avatar'; avatar.alt = '个人头像';
  const name = make('input'); name.maxLength = 32; name.value = readProfile().name || ctx.name();
  const nameLabel = make('label', '昵称'); nameLabel.append(name);
  const identity = make('p'); identity.className = 'hint profile-identity';
  const feedback = make('p'); feedback.className = 'hint'; feedback.setAttribute('role', 'status');
  const choose = make('input'); choose.type = 'file'; choose.accept = 'image/png,image/jpeg,image/webp,image/gif';
  const chooseLabel = make('label', '选择头像'); chooseLabel.append(choose);
  let draftAvatar = readProfile().avatarData, generation = 0, pending = false;
  const remove = make('button', '移除头像'), save = make('button', '保存个人资料');
  remove.type = save.type = 'button';
  function preview() { avatar.hidden = !draftAvatar; if (draftAvatar) avatar.src = draftAvatar; else avatar.removeAttribute('src'); }
  choose.onchange = async () => {
    const file = choose.files?.[0]; choose.value = ''; if (!file) return;
    const current = ++generation; pending = true; save.disabled = true;
    try { const data = await compressAvatar(file); if (current === generation) { draftAvatar = data; preview(); feedback.textContent = '头像已处理，点击保存后同步。'; } }
    catch (error) { if (current === generation) feedback.textContent = String(error); }
    finally { if (current === generation) { pending = false; save.disabled = false; } }
  };
  remove.onclick = () => { generation++; pending = false; save.disabled = false; draftAvatar = undefined; preview(); };
  save.onclick = async () => {
    if (pending) return;
    save.disabled = true;
    try {
      const profile = writeProfile({ name: name.value, avatarData: draftAvatar });
      await ctx.apply(profile);
      feedback.textContent = ctx.online() ? '已保存。头像同步请求已发送；昵称在下次进房生效。' : '已保存在本浏览器，下次进房使用。';
    } catch (error) { feedback.textContent = String(error); ctx.status(String(error), true); }
    finally { save.disabled = pending; }
  };
  const actions = make('div'); actions.className = 'community-tool-actions'; actions.append(remove, save);
  container.append(make('h3', '个人资料'), make('p', '头像沿用原版加密消息同步。昵称与头像保存在此浏览器，不上传到资料服务器。'), avatar, nameLabel, chooseLabel, actions, feedback, identity);
  preview();
  return { refresh() { identity.textContent = ctx.identity() ? `当前身份：${ctx.identity()}\n虚拟 IP：${ctx.ip() || '未就绪'}` : '加入大厅后显示当前身份与虚拟 IP。'; } };
}
