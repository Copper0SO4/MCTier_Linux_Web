// UI availability is independent of transport success or release acceptance.
// Enabling a feature requires wiring its real implementation and documenting validation.
export type FeatureState = 'experimental' | 'blocked';
export const FEATURES = [
  {
    id: 'network',
    name: '虚拟组网与大厅',
    state: 'experimental',
    reason: '用户已实测组网正常；信令大厅与 EasyTier 虚拟数据链路仍需分别判断。',
  },
  {
    id: 'chat',
    name: '文字聊天与附件下载',
    state: 'experimental',
    reason: '用户已实测消息收发正常；附件最大 64 MiB，消息经 EasyTier 数据链路传输。',
  },
  {
    id: 'voice',
    name: '麦克风与变声',
    state: 'experimental',
    reason: '用户已实测双向麦克风正常；ICE 诊断和长时间断线恢复尚未专项验收。',
  },
  {
    id: 'screen',
    name: '屏幕共享与观看手机',
    state: 'experimental',
    reason: '用户已实测双向屏幕共享正常；长期稳定性和断线重连尚未专项验收。',
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
    state: 'experimental',
    reason: '目录快照已接入原版 HTTP 共享；单文件 64 MiB，总量 256 MiB。跨端访问和凭据轮换待验收。',
  },
  {
    id: 'remote-control',
    name: '远程操控',
    state: 'blocked',
    reason: 'Linux Web 产品范围明确不支持远程输入操控；可以观看和共享屏幕。',
  },
  { id: 'send-file', name: '发送文件', state: 'experimental', reason: '用户已实测文件收发正常；沿用原版附件协议，单文件上限 64 MiB。' },
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
    state: 'experimental',
    reason: '已接入掷骰子、本地倒计时和原版协同待办；跨端同步待验收。',
  },
  {
    id: 'lobby-management',
    name: '房主管理',
    state: 'experimental',
    reason: '已接入公告、人数上限、公开发布/撤销和移出成员；跨端与服务端结果仍待真实大厅验收。',
  },
  {
    id: 'invite',
    name: '大厅二维码 / 邀请',
    state: 'experimental',
    reason: '用户已确认大厅邀请码正常；二维码图片识别仍取决于浏览器 BarcodeDetector。',
  },
  {
    id: 'lobby-history',
    name: '常用大厅 / 公开广场',
    state: 'experimental',
    reason: '常用大厅、最近记录和原版公开广场查询已接入；广场缺少节点时明确禁止加入。',
  },
  {
    id: 'magic-dns',
    name: 'Magic DNS',
    state: 'experimental',
    reason: '原版身份域名映射已接入；预览确认后通过 pkexec 更新/清理 hosts。成员变更需手动更新，实际解析待验收。',
  },
  {
    id: 'advanced-network',
    name: '高级网络 / 游戏增强',
    state: 'experimental',
    reason: '已接入性能/P2P/KCP/QUIC、私有子网/出口客户端、回环端口转发、游戏快连和 Minecraft 查询。SOCKS5、提供出口和 LAN 广播桥未开放；新参数待验收。',
  },
  {
    id: 'network-fix',
    name: '一键修复网络',
    state: 'experimental',
    reason: '按实际 EasyTier 监听端口预览 ufw/firewalld 规则，确认后一次性 pkexec 授权并复核，可撤销。不能保证 NAT/P2P；系统授权与真实链路待验收。',
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
    state: 'experimental',
    reason: '已接入浅色、深色和跟随系统，保存本浏览器偏好；界面语言仍为中文，语言切换尚未开放。',
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
