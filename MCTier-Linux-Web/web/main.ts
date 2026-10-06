import { setupSettingsTabs } from './settingsTabs';
import { captureSession } from './sessionGuard';
import { readProfile, setupProfile } from './profile';
import { memberControls } from './memberControls';
import { renderStatistics } from './sessionStatistics';
import { refreshReportedDefault, versionDetectionStatus } from './versionUpdater';
import { isSafeImageDataUrl } from '../frontend-src/security/trustBoundary';
import { beginReportedVersion, endReportedVersion, savedReportedVersion } from './clientVersion';
import { setupCommunity } from './community';
import { setupNetworkPanel } from './networkPanel';
import { builtinEmojiUrl } from './builtinEmoji';
// Reuse upstream presentation without mounting its native-window lifecycle.
import '../frontend-src/components/MainWindow/MainWindow.css';
import '../frontend-src/components/LobbyForm/LobbyForm.css';
import '../frontend-src/components/MiniWindow/MiniWindow.css';
import '../frontend-src/components/ChatRoom/ChatRoom.css';
import './styles.css';
import './theme.css';
import { setupTheme } from './theme';
import { setupComposer } from './chatComposer';
import { voiceDataUrl, voiceMetadata } from '../frontend-src/services/chat/voiceMessage';
import { sniffImageMime } from '../frontend-src/services/chat/imageData';
import { setupShell } from './shell';
import { downloadAttachment } from './download';
import { parseChatAttachment, formatFileSize } from '../frontend-src/services/chat/fileAttachment';
import { probeSignaling } from './signalingProbe';
import { localBootstrap, localInvoke, renewLocalLease } from '../frontend-src/services/platform/localWeb';
import { webrtcClient } from '../frontend-src/services/webrtc/WebRTCClient';
import { p2pChatService } from '../frontend-src/services/chat/P2PChatService';
import { lobbySessionCoordinator, type LobbySessionTicket } from '../frontend-src/services/lobby/LobbySessionCoordinator';
import { recoverVirtualAddress } from '../frontend-src/services/lobby/virtualAddressRecovery';
import { prepareSignalingIdentity } from '../frontend-src/services/signaling/signalingIdentity';
import { audioDevices } from '../frontend-src/services/voice/audioDevices';
import { voiceChangerService, VOICE_PRESETS } from '../frontend-src/services/voice/voiceChangerService';
import { screenShareService } from '../frontend-src/services/screenShare/ScreenShareService';
import { useAppStore } from '../frontend-src/stores/appStore';
import type { Lobby, Player, ChatMessage } from '../frontend-src/types';

const el = <T extends HTMLElement = HTMLElement>(id: string): T => document.getElementById(id) as T;
const text = (id: string, value: string) => { el(id).textContent = value; };
const input = (id: string) => el<HTMLInputElement>(id).value;
const label = (value: unknown) => value instanceof Error ? value.message : String(value);
function notice(message: string, error = false) { text('notice', message); el('notice').classList.toggle('error', error); }
function controlState(online: boolean) {
  for (const id of ['mic', 'speaker', 'resume-audio', 'voice-group', 'send', 'chat-text', 'recipient', 'peer-query', 'share-screen', 'refresh-shares']) {
    (el(id) as HTMLButtonElement).disabled = !online;
  }
  for (const id of ['join', 'create']) el<HTMLButtonElement>(id).disabled = busy || online || !shell.canJoin || !serviceReady;
  el<HTMLButtonElement>('leave').disabled = !busy && !online;
  el<HTMLButtonElement>('signal-probe').disabled = busy || online || !serviceReady;
  for (const id of ['player-name', 'lobby-name', 'lobby-password', 'server-node', 'signaling-server']) el<HTMLInputElement>(id).readOnly = busy || online;
  shell.setSessionState(online ? 'online' : busy ? 'connecting' : 'idle');
  composer.update();
}
let busy = false, online = false, leaving = false, serviceReady = false, localId = '', localName = '', hostId = '', lobbyPublic = false;
let lobbyMaxPlayers: number | null = null;
let ticket: LobbySessionTicket | null = null;
let leaseTimer: number | null = null;
let rosterSyncTimer: number | null = null;
let localShare: string | null = null, viewedShare: string | null = null;
let viewGeneration = 0;
const downloads = new Set<AbortController>();
const downloadUrls = new Set<string>();
const previewUrls = new Set<string>();
const players = new Map<string, Player>();
const messages = new Map<string, ChatMessage>();
const receipts = new Map<string, string>();
let statsRunning = false;
let sessionStartedAt = 0, signalingReconnects = 0;
const shell = setupShell();
setupTheme();
const profilePanel = setupProfile({
  online: () => online, name: () => input('player-name'), identity: () => localId, ip: () => el('virtual-ip').textContent || '', status: notice,
  apply: async profile => {
    useAppStore.getState().updateConfig({ avatarData: profile.avatarData });
    if (!online && !busy) el<HTMLInputElement>('player-name').value = profile.name;
    if (online) {
      const me = players.get(localId); if (me) me.avatarData = profile.avatarData;
      useAppStore.getState().updatePlayerStatus(localId, { avatarData: profile.avatarData }); renderMembers();
      await p2pChatService.sendAvatar(profile.avatarData);
    }
  },
});
const settingsTabs = setupSettingsTabs();
useAppStore.getState().updateConfig({ avatarData: readProfile().avatarData });
for (const card of document.querySelectorAll('[data-feature="avatar"] .feature-card, #feature-matrix [data-feature-id="avatar"]')) {
  const action = document.createElement('button'); action.type = 'button'; action.className = 'feature-action'; action.textContent = '个人资料与统计';
  action.onclick = () => { shell.showSettings(); settingsTabs.profile(); el('profile-panel').scrollIntoView({ block: 'start' }); }; card.append(action);
}

async function sendOutgoing(message: Omit<ChatMessage, 'id' | 'playerId' | 'playerName' | 'timestamp'>, send: (id: string) => Promise<{delivered:number;total:number} | void>) {
  if (!online) throw new Error('请先加入大厅');
  const current = ticket, id = `msg-${localId}-${crypto.randomUUID()}`;
  const own: ChatMessage = {...message, id, playerId:localId, playerName:localName, timestamp:Date.now()};
  messages.set(id, own); receipts.set(id, '发送中'); renderMessages();
  try {
    const receipt = await send(id);
    if (ticket !== current || !online) return;
    receipts.set(id, receipt ? (receipt.total ? `送达 ${receipt.delivered}/${receipt.total}` : '本地消息 · 无对端') : '发送完成');
    notice(receipt && receipt.delivered < receipt.total ? '未送达全部目标，请检查对端链路。' : '发送完成', !!receipt && receipt.delivered < receipt.total);
  } catch (error) { if (ticket === current && online) { receipts.set(id, '发送失败'); notice(label(error), true); } }
  if (ticket === current && online) renderMessages();
}
const composer = setupComposer({
  online: () => online, recipient: () => input('recipient') || undefined, status: notice,
  text: (content, recipientId) => sendOutgoing({content, recipientId, type:'text'}, id => p2pChatService.sendTextMessage(content, id, recipientId)),
  file: (attachment, recipientId) => sendOutgoing({content:JSON.stringify(attachment), attachment, recipientId, type:'file'}, id => p2pChatService.sendFileMessage(attachment, id, recipientId)),
  image: (data, content, recipientId) => sendOutgoing({content, imageData:data, recipientId, type:'image'}, id => p2pChatService.sendImageMessage(data, content, id, recipientId)),
  voice: async (blob, duration, recipientId) => {
    const current = ticket;
    const data = voiceDataUrl(Array.from(new Uint8Array(await blob.arrayBuffer())), blob.type);
    if (!online || ticket !== current) return;
    await sendOutgoing({content:JSON.stringify({mime:blob.type, duration}), imageData:data, recipientId, type:'voice'}, id => p2pChatService.sendVoiceMessage(blob, duration, id, recipientId));
  },
});

const community = setupCommunity({
  online:()=>online, session:()=>ticket, busy:()=>busy, canJoin:()=>shell.canJoin,
  invite:()=>({name:input('lobby-name').trim(),password:input('lobby-password'),serverNode:input('server-node').trim(),signalingServer:input('signaling-server').trim()}),
  playerId:()=>localId,playerName:()=>input('player-name').trim(),players:()=>[...players.values()],status:notice,
  fill:(invite,playerName)=>{
    el<HTMLInputElement>('lobby-name').value=invite.name;el<HTMLInputElement>('lobby-password').value=invite.password;
    if(invite.serverNode)el<HTMLInputElement>('server-node').value=invite.serverNode;
    if(invite.signalingServer)el<HTMLInputElement>('signaling-server').value=invite.signalingServer;
    if(playerName)el<HTMLInputElement>('player-name').value=playerName;
    shell.showConnect();
  },
  isHost:()=>online && hostId===localId, publicState:()=>lobbyPublic,
  publish:(enabled,description)=>webrtcClient.setLobbyOptions({isPublic:enabled,description,serverNode:input('server-node').trim()}),
  mute: (id, muted) => online && hostId === localId && webrtcClient.setPlayerMuted(id, muted),
  transfer: id => online && hostId === localId && webrtcClient.transferHost(id),
  maxPlayers:()=>lobbyMaxPlayers,
  setMaxPlayers:(max)=>webrtcClient.setLobbyOptions({maxPlayers:max}),
  announcement:()=>useAppStore.getState().announcement,
  announce:async content=>{
    if (!online || hostId !== localId) throw new Error('只有当前房主可以发布公告');
    const current = ticket;
    const receipt = await localInvoke<{delivered:number;total:number}>('send_p2p_chat_message', {
      playerId:localId, playerName:'', content, messageType:'announce', imageData:null,
      peerIps:[...players.values()].filter(player=>player.id!==localId).map(player=>player.virtualIp),
    });
    if (!online || ticket !== current || hostId !== localId) throw new Error('大厅已改变，公告结果已丢弃');
    useAppStore.getState().setAnnouncement(content);
    return receipt;
  },
  text:(content)=>sendOutgoing({content,type:'text'},id=>p2pChatService.sendTextMessage(content,id)),
});

window.addEventListener('mctier-version-default', () => {
  const field = document.getElementById('reported-version-input') as HTMLInputElement | null;
  if (field && !field.hasAttribute('data-edited')) field.value = savedReportedVersion();
  const status = document.getElementById('reported-version-status'); if (status) status.textContent = versionDetectionStatus();
});
const networkPanel=setupNetworkPanel({online:()=>online,busy:()=>busy,players:()=>[...players.values()],restartNetwork,status:notice});

el('copy-ip').onclick = async () => {
  if (!online) return;
  try { await navigator.clipboard.writeText(el('virtual-ip').textContent || ''); notice('虚拟 IP 已复制。'); }
  catch { notice('浏览器未允许复制，请手动选择虚拟 IP。', true); }
};

function installMessageListener() {
  p2pChatService.onMessage(message => { if (!online) return; messages.set(message.id, message); renderMessages(); });
  p2pChatService.onAvatar((id, avatarData) => {
    if (!online || !players.has(id)) return;
    const data = isSafeImageDataUrl(avatarData) ? avatarData : undefined;
    players.get(id)!.avatarData = data;
    useAppStore.getState().updatePlayerStatus(id, { avatarData: data }); renderMembers();
  });
}
function renderMessages() {
  if (!messages.size) return;
  const container = el('messages');
  for (const url of previewUrls) URL.revokeObjectURL(url); previewUrls.clear();
  container.replaceChildren();
  const ordered = [...messages.values()].sort((a, b) => a.timestamp - b.timestamp).slice(-300);
  for (const message of ordered) {
    const row = document.createElement('div'); row.className = `message chat-message ${message.playerId === localId ? 'me own' : 'other'}`;
    const meta = document.createElement('div'); meta.className = 'meta';
    meta.textContent = `${message.playerName} · ${new Date(message.timestamp).toLocaleTimeString()}${message.recipientId ? ' · 私聊' : ''}${receipts.has(message.id) ? ' · ' + receipts.get(message.id) : ''}`;
    const content = document.createElement('div'); content.className = 'text message-content'; content.textContent = message.recalled ? '消息已撤回' : message.content;
    row.append(meta, content);
    const builtin = !message.recalled && message.type !== 'file' ? builtinEmojiUrl(message.content) : null;
    if (builtin) {
      content.textContent = '[内置表情]';
      const image = document.createElement('img');
      image.src = builtin; image.alt = '内置动画表情'; image.loading = 'lazy';
      image.onerror = () => { image.remove(); content.textContent = '[内置表情资源加载失败，请刷新页面或更新服务]'; };
      row.append(image);
    }

    if (message.imageData && message.type === 'image' && !message.recalled) { const image = document.createElement('img'); image.src = message.imageData; image.alt = '聊天图片'; row.append(image); }
    if (message.imageData && message.type === 'voice' && !message.recalled) { content.textContent = `语音 · ${voiceMetadata(message.content)?.duration.toFixed(1) || '?'} 秒`; const audio = document.createElement('audio'); audio.src = message.imageData; audio.controls = true; row.append(audio); }
    if (message.type === 'file' && !message.recalled) {
      const attachment = parseChatAttachment(message.content);
      content.textContent = attachment ? `${attachment.name} · ${formatFileSize(attachment.size)}` : '文件附件元数据无效';
      if (attachment) {
        const button = document.createElement('button'); button.textContent = '下载附件'; button.disabled = !online;
        button.onclick = async () => {
          const controller = new AbortController(); downloads.add(controller); button.disabled = true; button.textContent = '下载中…';
          try {
            const blob = await downloadAttachment(message.playerId, attachment, controller.signal);
            if (controller.signal.aborted || !online) return;
            const url = URL.createObjectURL(blob); downloadUrls.add(url);
            const link = document.createElement('a'); link.href = url; link.download = attachment.name; document.body.append(link); link.click(); link.remove();
            window.setTimeout(() => { URL.revokeObjectURL(url); downloadUrls.delete(url); }, 60000);
            notice(`已交给浏览器保存：${attachment.name}`);
          } catch (error) { if (!controller.signal.aborted) notice(`下载失败：${label(error)}`, true); }
          finally { downloads.delete(controller); button.disabled = !online; button.textContent = '下载附件'; }
        };
        row.append(button);
        if (['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(attachment.mime)) {
          const preview = document.createElement('button'); preview.textContent = '查看图片'; preview.disabled = !online;
          preview.onclick = async () => {
            const controller = new AbortController(); downloads.add(controller); preview.disabled = true;
            try {
              const blob = await downloadAttachment(message.playerId, attachment, controller.signal);
              const mime = sniffImageMime(new Uint8Array(await blob.slice(0, 12).arrayBuffer()));
              if (!mime) throw new Error('图片格式无效');
              if (controller.signal.aborted || !online || !row.isConnected) return;
              const url = URL.createObjectURL(new Blob([blob], {type:mime})); previewUrls.add(url);
              const image = document.createElement('img'); image.src = url; image.alt = attachment.name; row.append(image); preview.hidden = true;
            } catch (error) { if (!controller.signal.aborted) notice(`预览失败：${label(error)}`, true); }
            finally { downloads.delete(controller); preview.disabled = !online; }
          };
          row.append(preview);
        }
      }
    }
    container.append(row);
  }
  container.scrollTop = container.scrollHeight;
}
function renderMembers() {
  const members = el('members');
  const expanded = new Set([...members.querySelectorAll<HTMLLIElement>('li[data-player-id]')].filter(row => row.querySelector<HTMLDetailsElement>('details')?.open).map(row => row.dataset.playerId));
  members.replaceChildren();
  text('member-count', String(players.size));
  for (const action of document.querySelectorAll<HTMLButtonElement>('[data-feature="lobby-management"] .feature-action')) {
    action.disabled = !online || hostId !== localId;
    action.title = action.disabled ? '只有当前房主可以管理大厅' : '';
  }
  const recipient = el<HTMLSelectElement>('recipient'); const selected = recipient.value;
  recipient.replaceChildren(new Option('所有成员', ''));
  for (const player of players.values()) {
    const row = document.createElement('li'); row.className = 'mini-player-item'; row.dataset.playerId = player.id;
    const info = document.createElement('div'); info.className = 'mini-player-info';
    const avatar = document.createElement('span'); avatar.className = 'player-avatar';
    if (isSafeImageDataUrl(player.avatarData)) { const image = document.createElement('img'); image.src = player.avatarData; image.alt = `${player.name}的头像`; avatar.append(image); }
    else avatar.textContent = Array.from(player.name)[0] || '?';
    const details = document.createElement('div'); details.className = 'player-details';
    const name = document.createElement('div'); name.className = 'member-name mini-player-name';
    name.textContent = `${player.name}${player.id === localId ? '（你）' : ''}${player.id === hostId ? ' · 房主' : ''}`;
    const mic = document.createElement('span'); mic.textContent = useAppStore.getState().hostMutedPlayers.has(player.id) ? '房主禁言' : player.micEnabled ? '🎙' : '关麦'; name.append(mic);
    const address = document.createElement('small'); address.className = 'player-ip-row'; address.textContent = player.virtualIp || '地址未知';
    details.append(name, address); info.append(avatar, details); row.append(info);
    if (player.id !== localId) {
      recipient.append(new Option(player.name, player.id));
      const current = ticket;
      const controls = memberControls(player, { valid: () => online && ticket === current && players.has(player.id), host: () => online && hostId === localId, status: notice });
      (controls as HTMLDetailsElement).open = expanded.has(player.id); row.append(controls);
    }
    members.append(row);
  }
  recipient.value = [...recipient.options].some(option => option.value === selected) ? selected : '';
  if (!players.size) { const row = document.createElement('li'); row.className = 'empty'; row.textContent = '尚未进入大厅'; members.append(row); }
  window.dispatchEvent(new Event('mctier-lobby-options'));
}
function renderAnnouncement() {
  const banner = el('announcement');
  const announcement = useAppStore.getState().announcement;
  banner.textContent = announcement ? `📢 ${announcement}` : '';
  banner.hidden = !announcement;
}
useAppStore.subscribe((state, previous) => {
  if (state.announcement !== previous.announcement) renderAnnouncement();
});

webrtcClient.onPlayerJoined((id, name, virtualIp) => {
  const newcomer = online && !players.has(id), current = ticket;
  const player = { ...players.get(id), id, name, virtualIp, micEnabled: false, isMuted: false, joinedAt: new Date().toISOString() };
  players.set(id, player); useAppStore.getState().addPlayer(player); renderMembers();
  if (newcomer) {
    if (rosterSyncTimer) clearTimeout(rosterSyncTimer);
    rosterSyncTimer = window.setTimeout(()=>{
      rosterSyncTimer=null;
      if (online && ticket===current && players.has(id)) {
        void p2pChatService.sendAvatar(readProfile().avatarData).catch(error => { if (online && ticket === current) notice(`头像同步失败：${label(error)}`, true); });
        void community.syncCurrentTodos().catch(error=>{if(ticket===current && online)notice(`新成员待办同步失败：${label(error)}`,true);});
        const announcement = useAppStore.getState().announcement;
        if (hostId===localId && announcement) {
          const peerIps=[...players.values()].filter(player=>player.id!==localId).map(player=>player.virtualIp);
          void localInvoke<{delivered:number;total:number}>('send_p2p_chat_message', {playerId:localId,playerName:'',content:announcement,messageType:'announce',imageData:null,peerIps})
            .then(receipt=>{if(ticket===current && online && receipt.delivered<receipt.total)notice(`公告补发只送达 ${receipt.delivered}/${receipt.total}，请检查对端链路。`,true);})
            .catch(error=>{if(ticket===current && online)notice(`公告补发失败：${label(error)}`,true);});
        }
      }
    },1600);
  }
});
webrtcClient.onPlayerLeft(id => { players.delete(id); useAppStore.getState().removePlayer(id); renderMembers(); });
webrtcClient.onStatusUpdate((id, micEnabled) => { const player = players.get(id); if (player) player.micEnabled = micEnabled; renderMembers(); });
webrtcClient.onSignalingStatus((status, error) => {
  const labels = { connected: '已注册', connecting: '连接中', reconnecting: '重连中', disconnected: '未连接', failed: '注册失败' };
  if (online && status === 'connecting' && useAppStore.getState().signalingStatus !== 'connecting') signalingReconnects++;
  text('signal-state', labels[status] || status);
  useAppStore.getState().setSignalingStatus(status, error);
  if (status === 'connected' && online) { installMessageListener(); p2pChatService.startPolling(); void p2pChatService.sendAvatar(readProfile().avatarData).catch(error => notice(`头像重同步失败：${label(error)}`, true)); }
  if (busy && status === 'connecting') notice('正在建立浏览器 WebSocket 信令连接；失败时会按原版策略有限重试，可点击取消。');
  if (status === 'failed' && online) { void leave().then(() => notice(`信令注册失败：${error || '请重新加入'}`, true)); return; }
  if (error) notice(`信令：${error}`, true);
});
webrtcClient.onLobbyMeta(meta => {
  hostId = meta.hostId || ''; lobbyPublic = meta.isPublic === true; lobbyMaxPlayers = meta.maxPlayers ?? null;
  const store = useAppStore.getState(); store.setHostId(hostId || null); store.setMaxPlayers(lobbyMaxPlayers); store.setIsPublicLobby(lobbyPublic); store.setHostMutedPlayers(meta.mutedPlayers || []);
  renderMembers();
});
webrtcClient.onLobbyOptionsChanged((max,isPublic)=>{
  lobbyMaxPlayers=max; lobbyPublic=isPublic;
  const store=useAppStore.getState(); store.setMaxPlayers(max); store.setIsPublicLobby(isPublic);
  window.dispatchEvent(new Event('mctier-lobby-options'));
  notice(`信令服务已确认：人数上限 ${max ?? '不限'}，${isPublic ? '已公开' : '未公开'}。`);
});
webrtcClient.onMuteChanged((id, muted) => { useAppStore.getState().setHostMuted(id, muted); renderMembers(); if (id === localId) notice(muted ? '你已被房主语音禁言。文字聊天仍可使用。' : '房主已解除语音禁言，可手动开启麦克风。'); });
webrtcClient.onHostChanged(id => { hostId = id; useAppStore.getState().setHostId(id); renderMembers(); });
webrtcClient.onKicked(reason => { void leave().then(() => notice(`已离开大厅：${reason}`, true)); });
webrtcClient.onVersionError((current, minimum) => notice(`版本不符合信令要求：当前 ${current}，至少需要 ${minimum}`, true));
webrtcClient.onLocalStream(stream => {
  const track = stream?.getAudioTracks()[0];
  const enabled = !!track && track.readyState === 'live';
  useAppStore.getState().setMicEnabled(enabled);
  text('mic', enabled ? '关闭麦克风' : '开启麦克风'); el('mic').classList.toggle('active', enabled);
  text('capture-state', track ? `麦克风轨道：${track.readyState} · ${track.getSettings().sampleRate || '未知'} Hz · ${voiceChangerService.getPreset() === 'none' ? '原声旁路' : 'WebAudio 效果链'}` : '麦克风轨道已释放');
  const me = players.get(localId); if (me) me.micEnabled = enabled; renderMembers();
});

async function startNetwork(attempt: number): Promise<Lobby> {
  const result = await localInvoke<{ name: string; virtual_ip: string; automatic_virtual_ip: boolean }>('connect_lobby', {
    name: input('lobby-name').trim(), password: input('lobby-password'), playerName: input('player-name').trim(),
    serverNode: input('server-node').trim(), signalingServer: input('signaling-server').trim(), addressAttempt: attempt,
    networkSettings: networkPanel.settings(),
  });
  return { id: result.name, name: result.name, createdAt: new Date().toISOString(), virtualIp: result.virtual_ip,
    creatorVirtualIp: '', automaticVirtualIp: result.automatic_virtual_ip, addressAttempt: attempt,
    serverNode: input('server-node').trim(), signalingServer: input('signaling-server').trim() };
}
async function join(entryMode: 'create' | 'join') {
  if (!shell.canJoin) { notice(shell.blockedReason, true); return; }
  if (!serviceReady) { notice('本地服务尚未就绪，请确认服务已启动后刷新页面。', true); return; }
  if (busy || online) return;
  busy = true; useAppStore.getState().setAppState('connecting'); controlState(false); ticket = lobbySessionCoordinator.begin();
  const current = ticket;
  const action = entryMode === 'create' ? '创建' : '加入';
  leaseTimer = window.setInterval(() => { void renewLocalLease().catch(error => { if (online || busy) void leave().then(() => notice(label(error), true)); }); }, 10000);
  notice('正在读取本机签名身份；若用户密钥环锁定，请检查系统提示…');
  try {
    beginReportedVersion();
    const identity = await prepareSignalingIdentity();
    lobbySessionCoordinator.assertCurrent(current); localId = identity.clientId; localName = input('player-name').trim();
    notice('正在启动所选 EasyTier 节点并等待本机虚拟接口…');
    let lobby: Lobby = { ...await startNetwork(0), entryMode };
    for (;;) {
      lobbySessionCoordinator.assertCurrent(current);
      notice('虚拟接口已就绪，正在注册原版协议 v3 信令…');
      try { const password=await localInvoke<string>('resolve_lobby_password',{password:input('lobby-password')}); lobbySessionCoordinator.assertCurrent(current); await webrtcClient.initialize(localId, localName, lobby.name, password, undefined, false, lobby.signalingServer, current, lobby.entryMode); break; }
      catch (error) {
        const replacement = await recoverVirtualAddress(lobby, label(error), async attempt => {
          await localInvoke('leave_lobby');
          return startNetwork(attempt);
        }, () => lobbySessionCoordinator.isCurrent(current));
        if (!replacement) throw error;
        lobby = { ...replacement, entryMode: replacement.entryMode ?? entryMode };
      }
    }
    lobbySessionCoordinator.assertCurrent(current);
    online = true; busy = false; sessionStartedAt = Date.now(); signalingReconnects = 0;
    useAppStore.getState().setLobby({ ...lobby, entryMode: 'auto' });
    useAppStore.getState().setAppState('in-lobby');
    useAppStore.getState().setSignalingStatus('connected');
    const me = { id: localId, name: localName, avatarData: readProfile().avatarData, virtualIp: lobby.virtualIp, micEnabled: false, isMuted: false, joinedAt: new Date().toISOString() };
    players.set(localId, me); useAppStore.getState().addPlayer(me);
    installMessageListener(); p2pChatService.startPolling();
    void p2pChatService.sendAvatar(readProfile().avatarData).catch(error => notice(`头像同步失败：${label(error)}`, true));
    text('room-name', lobby.name); text('virtual-ip', lobby.virtualIp);
    text('chat-state', '本机聊天服务已配置 · 送达待对端验证');
    try { localStorage.setItem('mctier_linux_player_name', localName); } catch { /* Storage does not decide admission. */ }
    controlState(true); renderMembers();
    void community.record().catch(error=>notice(`大厅已加入，但最近记录保存失败：${label(error)}`,true));
    notice('信令已注册，EasyTier 虚拟接口已就绪。请分别验证数据收发与双向语音。');
  } catch (error) {
    const cancelled = !lobbySessionCoordinator.isCurrent(current);
    await leave();
    if (!cancelled) notice(`${action}失败：${label(error)}`, true);
  }
}
async function restartNetwork() {
  if (!online || busy || leaving) throw new Error('请等待当前房间操作完成。');
  busy = true; controlState(online);
  el<HTMLButtonElement>('leave').disabled = true;
  try {
    notice('正在重新建立 EasyTier 连接，数据传输暂时中断…');
    await localInvoke('restart_easytier_network');
    notice('EasyTier 已重新启动，虚拟接口就绪；请核对对端数据、P2P 路由及媒体恢复。');
  } finally { busy = false; controlState(online); }
}
async function leave() {
  if (leaving) return;
  leaving = true; online = false; sessionStartedAt = 0; signalingReconnects = 0; endReportedVersion(); community.reset(); networkPanel.reset(); composer.cancel(); stopViewing();
  for (const controller of downloads) controller.abort(); downloads.clear();
  for (const url of downloadUrls) URL.revokeObjectURL(url); downloadUrls.clear();
  for (const url of previewUrls) URL.revokeObjectURL(url); previewUrls.clear();
  if (ticket) lobbySessionCoordinator.cancel(ticket);
  ticket = null;
  if (leaseTimer) clearInterval(leaseTimer); leaseTimer = null;
  if (rosterSyncTimer) clearTimeout(rosterSyncTimer); rosterSyncTimer=null;
  try {
    const results = await Promise.allSettled([webrtcClient.cleanup(), localInvoke('leave_lobby')]);
    const failure = results.find(result => result.status === 'rejected');
    if (failure?.status === 'rejected') throw failure.reason;
  } catch (error) { notice(`清理请求失败：${label(error)}。服务将在浏览器租约过期后停止自有进程。`, true); }
  finally {
    busy = false; leaving = false; controlState(false); useAppStore.getState().clearLobby(); useAppStore.getState().setAppState('idle');
    players.clear(); messages.clear(); receipts.clear(); hostId = ''; lobbyPublic=false; lobbyMaxPlayers=null; renderMembers();
    localShare = null; viewedShare = null;
    const video = el<HTMLVideoElement>('screen-video'); video.pause(); video.srcObject = null; video.hidden = true;
    text('room-name', '等待连接'); text('virtual-ip', '—'); text('signal-state', '未连接'); text('media-state', '未建立');
    text('messages', '已退出大厅，聊天与媒体资源已释放。'); text('peer-report', ''); renderShares();
  }
}
el<HTMLFormElement>('lobby-form').onsubmit = event => { event.preventDefault(); void join((event as SubmitEvent).submitter?.id === 'create' ? 'create' : 'join'); };
el<HTMLFormElement>('lobby-form').addEventListener('invalid', event => {
  const field = event.target as HTMLInputElement;
  notice(`请检查${field.closest('label')?.firstChild?.textContent?.trim() || '输入项'}：${field.validationMessage}`, true);
}, true);
window.addEventListener('securitypolicyviolation', event => {
  if (event.effectiveDirective === 'connect-src') notice('浏览器内容安全策略阻止了一项连接；请复制控制台中的 CSP 错误。不会自动改用其它地址。', true);
});
el('signal-probe').onclick = async () => {
  const button = el<HTMLButtonElement>('signal-probe'); button.disabled = true;
  notice('正在检查当前信令地址的浏览器 WebSocket 握手，不启动 EasyTier、不注册房间…');
  try { notice(await probeSignaling(input('signaling-server').trim())); }
  catch (error) { notice(`信令握手检查失败：${label(error)}`, true); }
  finally { button.disabled = busy || online || !serviceReady; }
};
let exitPrompt = false;
el('leave').onclick = () => {
  if (exitPrompt || leaving) return;
  exitPrompt = true;
  void networkPanel.beforeLeave().then(confirmed => confirmed ? leave() : undefined)
    .catch(error => notice(label(error), true)).finally(() => { exitPrompt = false; });
};
el('mic').onclick = async () => {
  const next = !useAppStore.getState().micEnabled;
  el<HTMLButtonElement>('mic').disabled = true;
  try { await webrtcClient.setMicEnabled(next); useAppStore.getState().setMicEnabled(next); await refreshDevices(); }
  catch (error) { notice(`麦克风操作失败：${label(error)}。请检查浏览器权限和输入设备。`, true); }
  finally { el<HTMLButtonElement>('mic').disabled = !online; }
};
el('speaker').onclick = () => { const store = useAppStore.getState(); store.setGlobalMuted(!store.globalMuted); text('speaker', !store.globalMuted ? '恢复听音' : '全部静音'); };
el('resume-audio').onclick = () => webrtcClient.resumeAudioPlayback();
el<HTMLSelectElement>('voice-group').onchange = () => {
  const group = Number(input('voice-group')); useAppStore.getState().setMyVoiceGroup(group);
  void p2pChatService.sendControlMessage('voicegroup', String(group)).catch(error => notice(label(error), true));
};
el<HTMLFormElement>('chat-form').onsubmit = async event => {
  event.preventDefault(); const content = input('chat-text').trim(); if (!online || !content) return;
  const recipientId = input('recipient') || undefined;
  el<HTMLInputElement>('chat-text').value = '';
  await sendOutgoing({ content, recipientId, type: 'text' }, id => p2pChatService.sendTextMessage(content, id, recipientId));
};

async function refreshDevices() {
  const devices = await navigator.mediaDevices.enumerateDevices();
  for (const [name, kind, selected] of [['audio-input', 'audioinput', audioDevices.getInputDeviceId()], ['audio-output', 'audiooutput', audioDevices.getOutputDeviceId()]] as const) {
    const select = el<HTMLSelectElement>(name); select.replaceChildren(new Option('系统默认', ''));
    devices.filter(device => device.kind === kind).forEach((device, index) => select.append(new Option(device.label || `设备 ${index + 1}`, device.deviceId)));
    select.value = selected;
  }
  if (!('setSinkId' in HTMLMediaElement.prototype)) el<HTMLSelectElement>('audio-output').disabled = true;
}
el<HTMLSelectElement>('audio-input').onchange = async () => {
  audioDevices.setInputDeviceId(input('audio-input'));
  if (online && useAppStore.getState().micEnabled) {
    try { await webrtcClient.setMicEnabled(false); await webrtcClient.setMicEnabled(true); }
    catch (error) { notice(label(error), true); }
  }
};
el<HTMLSelectElement>('audio-output').onchange = async () => {
  try { await webrtcClient.applyOutputDeviceToAll(input('audio-output')); audioDevices.setOutputDeviceId(input('audio-output')); }
  catch (error) { notice(`扬声器选择失败：${label(error)}`, true); }
};
const presets = el<HTMLSelectElement>('voice-preset');
VOICE_PRESETS.forEach(preset => presets.append(new Option(preset.zh, preset.id)));
presets.value = voiceChangerService.getPreset();
presets.onchange = () => voiceChangerService.setPreset(presets.value as Parameters<typeof voiceChangerService.setPreset>[0]);

el('peer-query').onclick = async () => {
  try { const report = await localInvoke<{ peers: string }>('get_easytier_peers'); text('peer-report', report.peers || '暂无 EasyTier 对端'); }
  catch (error) { notice(label(error), true); }
};
async function refreshDiagnostics() {
  if (statsRunning) return; statsRunning = true;
  const valid = captureSession(() => ticket);
  const abort = new AbortController();
  const timeout = window.setTimeout(() => abort.abort(), 8000);
  let serviceAnswered = false;
  try {
    const response = await fetch('/api/status', { cache: 'no-store', signal: abort.signal }); if (!response.ok) throw new Error('本地服务不可用');
    const status = await response.json();
    if (!valid()) return;
    serviceAnswered = true; text('service-state', '本地服务在线');
    profilePanel.refresh();
    const snapshot = { online, startedAt: online ? sessionStartedAt : 0, members: players.size, sent: [...messages.values()].filter(m => m.playerId === localId).length, received: [...messages.values()].filter(m => m.playerId !== localId).length, failed: [...receipts.values()].filter(value => value === '发送失败' || /^送达 (\d+)\/(\d+)$/.test(value) && Number(value.match(/\d+/g)?.[0]) < Number(value.match(/\d+/g)?.[1])).length, reconnects: signalingReconnects, sentBytes: 0, receivedBytes: 0, mediaPeers: 0 };
    if (!online) renderStatistics(snapshot);
    const closed = webrtcClient.getSignalingDiagnostics();
    text('signal-details', closed ? `上次断开 ${closed.code} · 存活 ${(closed.aliveMs / 1000).toFixed(1)} 秒 · 收包间隔 ${(closed.silentMs / 1000).toFixed(1)} 秒 · 待处理 ${closed.queuedFrames} · ${closed.pongPending ? '等待心跳' : '无待收心跳'}` : '暂无断开记录');
    text('network-state', status.network.state === 'interface-ready' ? '虚拟接口就绪' : status.network.state === 'starting' ? 'EasyTier 启动中' : 'EasyTier 未启动');
    el('network-dot').classList.toggle('ready', status.network.state === 'interface-ready');
    if (online && !status.session) { await leave(); notice('EasyTier 已停止，浏览器会话已清理。请手动重新加入。', true); }
    if (!online) return;
    // A failed chat-auth sync resets the shared service during reconnect.
    // Reattach this UI before restarting reception after authentication returns.
    const receive = p2pChatService.getReceiveDiagnostics();
    if (receive.identityReady && receive.tokenReady && (!receive.listening || !receive.callbackAttached)) {
      installMessageListener(); p2pChatService.startPolling();
    }
    const chat = p2pChatService.getReceiveDiagnostics();
    const streamLabels: Record<string, string> = { stopped: '已停止', connecting: '连接中', open: '已连接', reconnecting: '重连中' };
    text('chat-state', `后端远端记录 ${status.chatReceive?.storedRemoteMessages ?? 0} 条 · 页面聊天 ${chat.receivedMessages} 条 · 消息流${streamLabels[chat.stream] || chat.stream}${chat.error ? ' · ' + chat.error : ''}`);
    renderShares();
    const diagnostics = await webrtcClient.getVoiceDiagnostics();
    if (!valid() || !online) return;
    snapshot.mediaPeers = diagnostics.filter(peer => peer.connection === 'connected').length;
    snapshot.sentBytes = diagnostics.reduce((sum, peer) => sum + peer.sentBytes, 0);
    snapshot.receivedBytes = diagnostics.reduce((sum, peer) => sum + peer.receivedBytes, 0);
    renderStatistics(snapshot);
    text('media-state', `${snapshot.mediaPeers}/${diagnostics.length} 已连接`);
    const container = el('voice-diagnostics'); container.replaceChildren();
    for (const peer of diagnostics) {
      const row = document.createElement('div'); row.className = 'diag-row';
      const reconnect = document.createElement('button'); reconnect.textContent = '重连语音';
      reconnect.onclick = () => { void webrtcClient.reconnectPeerVoice(peer.playerId).then(sent => notice(sent ? '已发起语音重新协商，等待双端音频恢复。' : '语音重连未发起，请检查信令连接。', !sent)); };
      const name = document.createElement('strong'); name.textContent = players.get(peer.playerId)?.name || peer.playerId.slice(0, 12);
      const detail = document.createElement('small'); detail.textContent = `连接 ${peer.connection} · ICE ${peer.ice} · SDP ${peer.signaling}\n所选候选对：${peer.selectedPair ? `${peer.localType}/${peer.remoteType} ${peer.protocol}` : '尚无'} · RTT ${peer.roundTripTime === undefined ? '—' : `${Math.round(peer.roundTripTime * 1000)} ms`}\n音频发送 ${peer.sentPackets} 包 / ${peer.sentBytes} 字节 · 接收 ${peer.receivedPackets} 包 / ${peer.receivedBytes} 字节${peer.playbackBlocked ? '\n播放受阻：' + peer.playbackError : ''}`;
      row.append(reconnect, name, detail); container.append(row);
    }
    if (!diagnostics.length) container.textContent = '暂无对端媒体连接。';
  } catch {
    if (valid()) {
      if (serviceAnswered) text('media-state', '媒体诊断暂不可用');
      else text('service-state', '本地服务不可达');
    }
  }
  finally { clearTimeout(timeout); statsRunning = false; }
}

function stopViewing() {
  viewGeneration += 1;
  if (viewedShare) screenShareService.stopViewingScreen(viewedShare);
  viewedShare = null;
  const video = el<HTMLVideoElement>('screen-video'); video.pause(); video.srcObject = null; video.hidden = true;
  el('stop-view').hidden = true; el('resume-screen').hidden = true;
  text('screen-status', '未观看屏幕');
}
async function viewShare(share: ReturnType<typeof screenShareService.getActiveShares>[number]) {
  const password = share.requirePassword ? window.prompt('请输入屏幕共享密码') : undefined;
  if (password === null) return;
  stopViewing(); const generation = viewGeneration; viewedShare = share.id;
  el('stop-view').hidden = false; text('screen-status', `正在连接 ${share.playerName} 的画面，等待对端响应…`); renderShares();
  try {
    const stream = await screenShareService.requestViewScreen(share.id, password);
    if (generation !== viewGeneration || !online) return;
    const video = el<HTMLVideoElement>('screen-video'); video.srcObject = stream; video.hidden = false;
    try { await video.play(); }
    catch { el('resume-screen').hidden = false; notice('画面已连接，点击「播放画面」继续。'); }
  } catch (error) {
    if (generation !== viewGeneration) return;
    stopViewing(); notice(`观看失败：${label(error)}`, true);
  } finally { renderShares(); }
}
function renderShares() {
  if (localShare && !screenShareService.getMyActiveShares().some(share => share.id === localShare)) { localShare = null; text('share-screen', '共享屏幕'); }
  const shares = screenShareService.getActiveShares();
  if (viewedShare && !shares.some(share => share.id === viewedShare)) stopViewing();
  const container = el('shares'); container.replaceChildren();
  for (const share of shares) {
    const row = document.createElement('div'); row.className = 'share';
    const title = document.createElement('span'); title.textContent = `${share.playerName} 的屏幕${share.requirePassword ? ' · 需要密码' : ''}`;
    const button = document.createElement('button'); button.textContent = share.playerId === localId ? '停止共享' : viewedShare === share.id ? '重新连接画面' : '观看';
    button.disabled = !online;
    button.onclick = () => {
      if (share.playerId === localId) { screenShareService.stopSharing(share.id); localShare = null; renderShares(); text('share-screen', '共享屏幕'); }
      else void viewShare(share);
    };
    row.append(title, button); container.append(row);
  }
  if (!shares.length) container.textContent = '暂无共享画面。观看手机时，请先在手机端开始屏幕共享并允许录屏，再刷新列表。';
  if (viewedShare) {
    const diagnostic = screenShareService.getViewingDiagnostics(viewedShare);
    const video = el<HTMLVideoElement>('screen-video');
    const frames = video.getVideoPlaybackQuality?.().totalVideoFrames ?? 0;
    text('screen-status', `${diagnostic.connections.map(pc => `连接 ${pc.connection} · ICE ${pc.ice} · SDP ${pc.signaling}`).join(' / ') || '等待对端分配观看路由'} · 视频轨道 ${diagnostic.track} · 已显示 ${frames} 帧 · ${video.videoWidth} × ${video.videoHeight}`);
  }
}
el('refresh-shares').onclick = () => { screenShareService.requestShareList(); notice('已请求成员的共享列表。手机端需先开始屏幕共享并允许录屏。'); };
el('resume-screen').onclick = async () => {
  try { await el<HTMLVideoElement>('screen-video').play(); el('resume-screen').hidden = true; }
  catch (error) { notice(`画面播放失败：${label(error)}`, true); }
};
el('share-screen').onclick = async () => {
  if (localShare) { screenShareService.stopSharing(localShare); localShare = null; text('share-screen', '共享屏幕'); renderShares(); return; }
  try { localShare = await screenShareService.startSharing(false); text('share-screen', '停止共享'); renderShares(); }
  catch (error) { notice(`屏幕共享未启动：${label(error)}`, true); }
};
el('stop-view').onclick = () => { stopViewing(); renderShares(); };
for (const name of ['screen-share-start', 'screen-share-stop']) window.addEventListener(name, () => { renderShares(); });
window.addEventListener('mctier-microphone-permission-required', () => { useAppStore.getState().setMicEnabled(false); text('mic', '重新开启麦克风'); notice('麦克风权限或设备已失效，音轨已关闭。请手动重新授权。', true); });
window.addEventListener('pagehide', () => {
  stopViewing(); for (const controller of downloads) controller.abort();
  for (const url of downloadUrls) URL.revokeObjectURL(url);
  for (const url of previewUrls) URL.revokeObjectURL(url);
  // Synchronously release capture even when the browser cannot finish requests.
  void webrtcClient.setMicEnabled(false); screenShareService.cleanup(); lobbySessionCoordinator.cancel();
  void localInvoke('leave_lobby', {}, true).catch(() => {});
  void webrtcClient.cleanup();
});

async function initialize() {
  try {
    const boot = await localBootstrap();
    serviceReady = true;
    controlState(false);
    el<HTMLInputElement>('server-node').value = boot.defaults.serverNode;
    el<HTMLInputElement>('signaling-server').value = boot.defaults.signalingServer;
    try { el<HTMLInputElement>('player-name').value = readProfile().name || localStorage.getItem('mctier_linux_player_name') || ''; } catch { /* User can enter a name without storage. */ }
    // No signing identity, device permission, node or signaling connection at startup.
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) notice('当前浏览器上下文不能申请麦克风；请访问准确的回环地址。', true);
    text('service-state', '本地服务在线');
    const response = await fetch('/api/status', { cache: 'no-store' }); const status = await response.json();
    if (status.session) notice('本地服务已有大厅会话。若是本页面刷新留下的会话，请先点击退出，再手动重新加入。');
    if (status.session) {
      shell.setSessionState('orphaned');
      el<HTMLButtonElement>('leave').disabled = false;
      for (const id of ['join', 'create']) el<HTMLButtonElement>(id).disabled = true;
      el<HTMLButtonElement>('signal-probe').disabled = true;
    }
    void refreshReportedDefault().catch(error => notice(label(error), true));
    void refreshDiagnostics();
    window.setInterval(() => { void refreshDiagnostics(); }, 2500);
  } catch (error) { serviceReady = false; controlState(false); text('service-state', '本地服务不可达'); notice(`本地服务初始化失败：${label(error)}`, true); }
}
controlState(false);
renderMembers();
void initialize();
