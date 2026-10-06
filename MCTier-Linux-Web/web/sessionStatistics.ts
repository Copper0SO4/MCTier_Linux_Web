export type SessionSnapshot = { online: boolean; startedAt: number; members: number; sent: number; received: number; failed: number; sentBytes: number; receivedBytes: number; mediaPeers: number; reconnects: number };
export function statisticsRows(snapshot: SessionSnapshot, now = Date.now()): [string, string][] {
  const seconds = snapshot.startedAt ? Math.max(0, Math.floor((now - snapshot.startedAt) / 1000)) : 0;
  const bytes = (value: number) => `${(Math.max(0, value) / 1024).toFixed(1)} KiB`;
  return [
    ['会话状态', snapshot.online ? '大厅内' : '未连接'], ['本次在线时长', `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`],
    ['当前成员', String(snapshot.members)], ['页面发出消息', String(snapshot.sent)], ['页面收到消息（含历史补收）', String(snapshot.received)],
    ['发送失败或部分送达', String(snapshot.failed)], ['信令重连次数', String(snapshot.reconnects)],
    ['已连接语音对端', String(snapshot.mediaPeers)], ['当前语音连接发送 RTP', bytes(snapshot.sentBytes)], ['当前语音连接接收 RTP', bytes(snapshot.receivedBytes)],
  ];
}
export function renderStatistics(snapshot: SessionSnapshot): void {
  const container = document.getElementById('statistics-panel')!;
  const heading = document.createElement('h3'); heading.textContent = '本次会话统计';
  const grid = document.createElement('dl'); grid.className = 'statistics-grid';
  for (const [label, value] of statisticsRows(snapshot)) {
    const term = document.createElement('dt'), detail = document.createElement('dd'); term.textContent = label; detail.textContent = value; grid.append(term, detail);
  }
  const note = document.createElement('p'); note.className = 'hint'; note.textContent = '仅本页会话，不上传、不跨会话累计。RTP 是当前语音连接的累计值，重新协商后可能归零；不含屏幕、文件流量，也不是 EasyTier 总流量。';
  container.replaceChildren(heading, grid, note);
}
