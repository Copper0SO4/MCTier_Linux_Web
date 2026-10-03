// UI availability is independent of transport success or release acceptance.
// Enabling a feature requires wiring its real implementation and documenting validation.
export type FeatureState = 'experimental' | 'blocked';
export const FEATURES = [
  {
    id: 'network',
    name: '虚拟组网与大厅',
    state: 'experimental',
    reason: '已接入原版节点和信令协议；接口、对端收发仍需验收。',
  },
  {
    id: 'chat',
    name: '文字聊天与附件下载',
    state: 'experimental',
    reason: '公开、私聊和附件下载已接入；附件最大 64 MiB，跨端仍需验收。',
  },
  {
    id: 'voice',
    name: '麦克风与变声',
    state: 'experimental',
    reason: '已接入浏览器音频；双向语音、ICE、断线恢复仍需验收。',
  },
  {
    id: 'screen',
    name: '屏幕共享与观看手机',
    state: 'experimental',
    reason: '可共享视频和观看对端画面；手机互看与重连仍需验收。',
  },
  {
    id: 'firefox',
    name: 'Firefox 大厅连接',
    state: 'blocked',
    reason:
      '已知信令 WebSocket 1006 持续断开，干净配置也曾复现；根因尚未确认。请使用 Chrome/Chromium。',
  },
  {
    id: 'folder-share',
    name: '文件夹共享',
    state: 'blocked',
    reason: '浏览器授权目录、共享管理与传输列表尚未接入。聊天附件下载不受此项影响。',
  },
  {
    id: 'remote-control',
    name: '远程操控',
    state: 'blocked',
    reason: '远程输入授权与 Linux 输入注入尚未实现；仅开放屏幕观看。',
  },
  { id: 'send-file', name: '发送文件', state: 'experimental', reason: '已接入 64 MiB 附件上传及原版加密取件协议；跨端送达待验收。' },
  {
    id: 'send-image',
    name: '发送图片 / 表情',
    state: 'experimental',
    reason: '支持 PNG/JPEG/GIF/WebP、文字表情、原版内置动画和图片表情；跨端待验收。',
  },
  {
    id: 'record-voice',
    name: '语音消息',
    state: 'experimental',
    reason: '支持 0.5–60 秒录音、取消和播放；跨端编码兼容待验收。',
  },
  {
    id: 'system-audio',
    name: '系统音频 / 屏幕录制',
    state: 'blocked',
    reason: '当前仅采集屏幕视频；系统音频和录像保存尚未接入。',
  },
  {
    id: 'room-tools',
    name: '房间工具',
    state: 'blocked',
    reason: '掷骰子、倒计时、协同待办的界面尚未迁移。',
  },
  {
    id: 'lobby-management',
    name: '房主管理',
    state: 'blocked',
    reason: '公告、人数限制、公开大厅管理尚未迁移；现有移出成员操作仍保留。',
  },
  {
    id: 'invite',
    name: '大厅二维码 / 邀请',
    state: 'blocked',
    reason: '二维码、邀请链接的导入和生成尚未接入。',
  },
  {
    id: 'lobby-history',
    name: '常用大厅 / 公开广场',
    state: 'blocked',
    reason: '收藏、历史、公开大厅列表和加入入口尚未接入。',
  },
  {
    id: 'magic-dns',
    name: 'Magic DNS',
    state: 'blocked',
    reason: '虚拟域名和 DNS 安装尚未迁移；请使用虚拟 IP。',
  },
  {
    id: 'advanced-network',
    name: '高级网络 / 游戏增强',
    state: 'blocked',
    reason: '高级参数、出口节点、端口转发和游戏发现界面尚未接入。',
  },
  {
    id: 'network-fix',
    name: '一键修复网络',
    state: 'blocked',
    reason: '防火墙修改与网络修复尚未迁移；只开放现有诊断。',
  },
  {
    id: 'desktop-integration',
    name: '托盘 / 快捷键 / 悬浮窗',
    state: 'blocked',
    reason: '浏览器版尚无桌面托盘、全局快捷键、弹幕或 HUD。',
  },
  {
    id: 'appearance',
    name: '主题 / 界面语言',
    state: 'blocked',
    reason: '当前使用原版深色样式和中文界面；设置切换尚未接入。',
  },
  {
    id: 'avatar',
    name: '个人头像 / 本地统计',
    state: 'blocked',
    reason: '头像上传、个人资料和统计页面尚未接入。',
  },
  {
    id: 'auto-lobby',
    name: '自动加入 / 自动更新',
    state: 'blocked',
    reason: '服务就绪后需手动连接大厅；后台启动、自动加入和更新安装尚未接入。',
  },
] as const satisfies readonly { id: string; name: string; state: FeatureState; reason: string }[];

export function browserAvailability(userAgent: string): { canJoin: boolean; warning: string } {
  if (/Firefox\//i.test(userAgent))
    return {
      canJoin: false,
      warning: FEATURES.find((feature) => feature.id === 'firefox')!.reason,
    };
  return {
    canJoin: true,
    warning: 'Linux 浏览器版为实验性支持，推荐 Chrome/Chromium；双向语音和跨端稳定性仍需验收。',
  };
}
