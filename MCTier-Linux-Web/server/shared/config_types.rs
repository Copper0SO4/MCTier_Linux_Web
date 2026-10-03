//! Data-only configuration types, shared with the Linux local service.
use serde::{Deserialize, Serialize};
/// 窗口位置信息
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct WindowPosition {
    /// X 坐标
    pub x: i32,
    /// Y 坐标
    pub y: i32,
    /// 窗口宽度
    pub width: u32,
    /// 窗口高度
    pub height: u32,
}

impl Default for WindowPosition {
    fn default() -> Self {
        Self {
            x: 100,
            y: 100,
            width: 300,
            height: 400,
        }
    }
}

/// 自动大厅配置
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct AutoLobbyConfig {
    /// 是否启用自动创建/加入大厅
    pub enabled: bool,
    /// 大厅名称
    pub lobby_name: Option<String>,
    /// 大厅密码
    #[serde(default)]
    pub lobby_password: Option<String>,
    /// 玩家名称
    pub player_name: Option<String>,
    /// 是否使用虚拟域名
    pub use_domain: bool,
    /// 虚拟域名
    pub virtual_domain: Option<String>,
}

/// EasyTier 节点配置
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct EasyTierNode {
    /// 节点名称
    pub name: String,
    /// 节点地址
    pub address: String,
}

/// 端口转发规则
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PortForwardRule {
    /// 协议类型（tcp/udp）
    pub protocol: String,
    /// 本地绑定地址（例如：0.0.0.0:5678）
    pub bind_addr: String,
    /// 目标地址（例如：10.2.2.1:5678）
    pub dst_addr: String,
}

/// EasyTier 高级配置
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct EasyTierAdvancedConfig {
    // ========== 配置来源 ==========
    /// 是否使用全局配置（仅用于大厅配置）
    pub use_global_config: bool,

    // ========== 网络模式 ==========
    /// 是否启用无 TUN 模式（不创建虚拟网卡）
    pub no_tun: bool,
    /// 是否启用 DHCP 自动分配 IP
    pub dhcp: bool,
    /// 手动指定的虚拟 IPv4 地址
    pub ipv4: Option<String>,

    // ========== 代理和转发 ==========
    /// 是否启用 SOCKS5 代理
    pub enable_socks5: bool,
    /// SOCKS5 代理端口
    pub socks5_port: Option<u16>,
    /// 端口转发规则列表
    pub port_forward_rules: Vec<PortForwardRule>,
    /// 是否通过系统内核转发子网代理数据包
    pub proxy_forward_by_system: bool,
    /// 子网代理 CIDR 列表（导出本地网络）
    pub proxy_networks: Vec<String>,

    // ========== 出口节点 ==========
    /// 是否启用作为出口节点
    pub enable_as_exit_node: bool,
    /// 出口节点列表（使用其他节点作为出口）
    pub exit_nodes: Vec<String>,

    // ========== 性能优化 ==========
    /// 是否启用多线程
    pub multi_thread: bool,
    /// 多线程数量（默认2）
    pub multi_thread_count: Option<u32>,
    /// 是否启用延迟优先模式
    pub latency_first: bool,
    /// 是否启用 smoltcp 堆栈
    pub use_smoltcp: bool,

    // ========== 协议优化 ==========
    /// 是否启用 KCP 代理
    pub enable_kcp_proxy: bool,
    /// 是否禁用 KCP 输入
    pub disable_kcp_input: bool,
    /// 是否启用 QUIC 代理
    pub enable_quic_proxy: bool,
    /// 是否禁用 QUIC 输入
    pub disable_quic_input: bool,
    /// QUIC 监听端口
    pub quic_listen_port: Option<u16>,

    // ========== 加密和安全 ==========
    /// 是否禁用加密
    pub disable_encryption: bool,
    /// 加密算法（aes-gcm, aes-256-gcm, xor, chacha20）
    pub encryption_algorithm: Option<String>,

    // ========== 网络设备 ==========
    /// 是否绑定到物理设备
    pub bind_device: bool,
    /// TUN 设备名称
    pub dev_name: Option<String>,
    /// MTU 大小
    pub mtu: Option<u32>,

    // ========== P2P 配置 ==========
    /// 是否仅使用 P2P 连接
    pub p2p_only: bool,
    /// 是否禁用 P2P
    pub disable_p2p: bool,
    /// 是否禁用 UDP 打洞
    pub disable_udp_hole_punching: bool,
    /// 是否禁用 TCP 打洞
    pub disable_tcp_hole_punching: bool,
    /// 是否禁用对称 NAT 打洞
    pub disable_sym_hole_punching: bool,

    // ========== 中继配置 ==========
    /// 中继网络白名单（支持通配符）
    pub relay_network_whitelist: Vec<String>,
    /// 是否转发所有对等节点的 RPC
    pub relay_all_peer_rpc: bool,
    /// 是否禁用中继 KCP
    pub disable_relay_kcp: bool,
    /// 是否启用中继外部网络 KCP
    pub enable_relay_foreign_network_kcp: bool,
    /// 外部网络流量转发速率限制（BPS）
    pub foreign_relay_bps_limit: Option<u64>,

    // ========== 路由配置 ==========
    /// 手动分配的路由 CIDR
    pub manual_routes: Vec<String>,

    // ========== 压缩 ==========
    /// 压缩算法（none, zstd）
    pub compression: Option<String>,

    // ========== 监听器配置 ==========
    /// 监听器列表
    pub listeners: Vec<String>,
    /// 映射的监听器（公网地址）
    pub mapped_listeners: Vec<String>,
    /// 是否不监听任何端口
    pub no_listener: bool,
    /// 默认协议
    pub default_protocol: Option<String>,

    // ========== DNS 配置 ==========
    /// 是否启用魔法 DNS
    pub accept_dns: bool,
    /// 顶级域名区域
    pub tld_dns_zone: Option<String>,

    // ========== 端口白名单 ==========
    /// TCP 端口白名单
    pub tcp_whitelist: Vec<String>,
    /// UDP 端口白名单
    pub udp_whitelist: Vec<String>,

    // ========== IPv6 ==========
    /// 是否禁用 IPv6
    pub disable_ipv6: bool,
    /// 虚拟 IPv6 地址
    pub ipv6: Option<String>,

    // ========== STUN 服务器 ==========
    /// 自定义 STUN 服务器列表
    pub stun_servers: Vec<String>,
    /// 自定义 IPv6 STUN 服务器列表
    pub stun_servers_v6: Vec<String>,

    // ========== 私有模式 ==========
    /// 是否启用私有模式
    pub private_mode: bool,
}

impl Default for EasyTierAdvancedConfig {
    fn default() -> Self {
        Self {
            // 配置来源
            use_global_config: true,

            // 网络模式
            no_tun: false,
            dhcp: true,
            ipv4: None,

            // 代理和转发
            enable_socks5: false,
            socks5_port: None,
            port_forward_rules: Vec::new(),
            proxy_forward_by_system: false,
            proxy_networks: Vec::new(),

            // 出口节点
            enable_as_exit_node: false,
            exit_nodes: Vec::new(),

            // 性能优化
            multi_thread: true,
            multi_thread_count: Some(2),
            latency_first: true,
            use_smoltcp: false,

            // 协议优化
            enable_kcp_proxy: false,
            disable_kcp_input: false,
            enable_quic_proxy: false,
            disable_quic_input: false,
            quic_listen_port: None,

            // 加密和安全
            disable_encryption: false,
            encryption_algorithm: None,

            // 网络设备
            bind_device: false,
            dev_name: Some("MCTier_Net".to_string()),
            mtu: None,

            // P2P 配置
            p2p_only: false,
            disable_p2p: false,
            disable_udp_hole_punching: false,
            disable_tcp_hole_punching: false,
            disable_sym_hole_punching: false,

            // 中继配置
            relay_network_whitelist: Vec::new(),
            relay_all_peer_rpc: false,
            disable_relay_kcp: false,
            enable_relay_foreign_network_kcp: false,
            foreign_relay_bps_limit: None,

            // 路由配置
            manual_routes: Vec::new(),

            // 压缩
            compression: None,

            // 监听器配置
            listeners: Vec::new(),
            mapped_listeners: Vec::new(),
            no_listener: false,
            default_protocol: None,

            // DNS 配置
            accept_dns: false,
            tld_dns_zone: None,

            // 端口白名单
            tcp_whitelist: Vec::new(),
            udp_whitelist: Vec::new(),

            // IPv6
            disable_ipv6: false,
            ipv6: None,

            // STUN 服务器
            stun_servers: Vec::new(),
            stun_servers_v6: Vec::new(),

            // 私有模式
            private_mode: false,
        }
    }
}

/// 出口节点配置（已废弃，保留用于兼容性）
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct ExitNodeConfig {
    /// 是否启用出口节点功能
    pub enable_exit_node: bool,
    /// 本机作为出口节点
    pub enable_as_exit_node: bool,
    /// 出口网段列表
    pub proxy_cidrs: Vec<String>,
    /// 客户端出口节点列表（虚拟 IPv4）
    pub exit_nodes: Vec<String>,
    /// 子网代理CIDR列表（用于共享本地子网）
    pub subnet_proxy_cidrs: Vec<String>,

    // ========== 新增高级配置 ==========
    /// 是否启用 SOCKS5 代理
    pub enable_socks5: bool,
    /// SOCKS5 代理端口
    pub socks5_port: Option<u16>,
    /// 端口转发规则列表
    pub port_forward_rules: Vec<PortForwardRule>,
    /// 是否启用无 TUN 模式
    pub no_tun: bool,
    /// 是否启用系统转发
    pub proxy_forward_by_system: bool,
    /// 是否仅使用物理网卡
    pub bind_device: bool,
    /// 是否启用多线程
    pub multi_thread: bool,
    /// 多线程数量（默认2）
    pub multi_thread_count: Option<u32>,
    /// 是否启用 smoltcp
    pub use_smoltcp: bool,
    /// 是否启用 KCP 代理
    pub enable_kcp_proxy: bool,
    /// 是否启用 QUIC 代理
    pub enable_quic_proxy: bool,
    /// 是否启用延迟优先模式
    pub latency_first: bool,
}

/// 用户配置结构
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct UserConfig {
    /// 界面语言偏好（system/zh/en）
    pub language: Option<String>,
    /// 玩家名称
    pub player_name: Option<String>,
    /// 用户自定义头像数据（压缩后的 data URL）
    pub avatar_data: Option<String>,
    /// 首选服务器节点
    pub preferred_server: Option<String>,
    /// 麦克风开关快捷键
    pub mic_hotkey: Option<String>,
    /// 全局听筒快捷键
    pub global_mute_hotkey: Option<String>,
    /// 临时开麦（按住说话）快捷键
    pub push_to_talk_hotkey: Option<String>,
    /// 唤出主窗口快捷键（从系统托盘/最小化状态唤回）
    pub summon_hotkey: Option<String>,
    /// 状态窗口位置
    pub window_position: Option<WindowPosition>,
    /// 音频设备 ID
    pub audio_device_id: Option<String>,
    /// 窗口透明度 (0.0-1.0)，默认 0.95
    pub opacity: Option<f64>,
    /// 是否开机自启
    pub auto_startup: Option<bool>,
    /// 自动大厅配置
    pub auto_lobby: Option<AutoLobbyConfig>,
    /// 是否使用私有服务器
    pub use_private_server: Option<bool>,
    /// 私有 EasyTier 节点服务器地址
    pub private_easytier_server: Option<String>,
    /// 私有信令服务器地址
    pub private_signaling_server: Option<String>,
    /// 窗口是否置顶，默认 true
    pub always_on_top: Option<bool>,
    /// 是否记住窗口位置，默认 false
    pub remember_window_position: Option<bool>,
    /// 关闭窗口时最小化到系统托盘（而非退出程序），默认 false
    pub close_to_tray: Option<bool>,
    /// 启动后自动隐藏到系统托盘（后台运行），默认 false
    pub start_minimized: Option<bool>,
    /// 自定义 EasyTier 节点列表
    pub custom_easytier_nodes: Option<Vec<EasyTierNode>>,
    /// 语音音量 (0.0-1.0)，默认 1.0
    pub voice_volume: Option<f64>,
    /// 是否启用 GPU 渲染，默认 true
    pub enable_gpu_rendering: Option<bool>,
    /// 出口节点配置（已废弃，保留用于兼容性）
    pub exit_node_config: Option<ExitNodeConfig>,
    /// 全局 EasyTier 高级配置
    pub global_easytier_advanced_config: Option<EasyTierAdvancedConfig>,
    /// 大厅 EasyTier 高级配置（覆盖全局配置）
    pub lobby_easytier_advanced_config: Option<EasyTierAdvancedConfig>,
    /// 文件夹共享下载目录；为空时使用系统下载目录下的 MCTier 文件夹
    pub file_share_download_dir: Option<String>,
}

impl Default for UserConfig {
    fn default() -> Self {
        Self {
            language: None,
            player_name: None,
            avatar_data: None,
            preferred_server: None,
            mic_hotkey: Some("Ctrl+M".to_string()),
            global_mute_hotkey: Some("Ctrl+T".to_string()),
            push_to_talk_hotkey: Some("F2".to_string()),
            summon_hotkey: Some("Ctrl+Alt+M".to_string()),
            window_position: Some(WindowPosition::default()),
            audio_device_id: None,
            opacity: Some(0.95),
            auto_startup: Some(false),
            auto_lobby: Some(AutoLobbyConfig::default()),
            use_private_server: Some(false),
            private_easytier_server: Some("tcp://easytier.weiai.org.cn:11010".to_string()),
            private_signaling_server: Some("wss://mctier.pmhs.top/signaling".to_string()),
            always_on_top: Some(true),
            remember_window_position: Some(false),
            close_to_tray: Some(false),
            start_minimized: Some(false),
            custom_easytier_nodes: Some(Vec::new()),
            voice_volume: Some(1.0),
            enable_gpu_rendering: Some(true),
            exit_node_config: Some(ExitNodeConfig::default()),
            global_easytier_advanced_config: None,
            lobby_easytier_advanced_config: None,
            file_share_download_dir: None,
        }
    }
}

impl UserConfig {
    pub(crate) fn migrate_legacy_signaling_server(&mut self) {
        let legacy = self.private_signaling_server.as_deref().map(str::trim);
        if matches!(
            legacy.map(|url| url.trim_end_matches('/')),
            Some(
                "ws://test.pmhs.top"
                    | "wss://test.pmhs.top"
                    | "ws://test.pmhs.top/signaling"
                    | "wss://test.pmhs.top/signaling"
            )
        ) {
            self.private_signaling_server = Some("wss://mctier.pmhs.top/signaling".to_string());
        }
    }
}
