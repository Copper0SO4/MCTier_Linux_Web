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
    reason: '已接入公告、人数上限、公开发布/撤销、移出、房主转让与语音禁言；跨端与服务端结果仍待真实大厅验收。',
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
    id: 'advanced-network',
    name: '高级网络 / 游戏增强',
    state: 'experimental',
    reason: '已接入性能/P2P/KCP/QUIC、网络栈、中继、STUN、IPv6、路由、私有子网/出口、回环转发与游戏查询。SOCKS5、任意监听器和 LAN 广播桥未开放；新参数待验收。',
  },
  {
    id: 'network-fix',
    name: '一键修复网络',
    state: 'experimental',
    reason: '按实际 TCP/UDP 监听预览 ufw/firewalld 规则，确认授权并复核后自动重连 EasyTier。用户已实测 UFW 放行后重连可 P2P；其它 NAT/对端及 firewalld 仍需验收。',
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
    name: '个人资料 / 头像 / 本地统计',
    state: 'experimental',
    reason: '昵称与头像保存于本浏览器，头像沿用原版加密控制消息同步；提供本次会话消息和语音传输统计，跨端待验收。',
  },
  {
    id: 'auto-lobby',
    name: '启动自动组网 / 浏览器接续',
    state: 'experimental',
    reason: '软件设置中的大厅与邀请可保存启动目标；服务启动自动运行 EasyTier，打开网页后注册大厅信令。默认关闭，不自动开启媒体或授权。真实启动接续待验收。',
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
