//! A bounded Linux Web UI adapter for the original EasyTier config/argv logic.
use crate::modules::{
    config_manager::{EasyTierAdvancedConfig, PortForwardRule},
    virtual_network::virtual_host,
};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase", deny_unknown_fields)]
pub struct NetworkSettings {
    pub use_smoltcp: bool,
    pub disable_kcp_input: bool,
    pub disable_quic_input: bool,
    pub proxy_forward_by_system: bool,
    pub enable_as_exit_node: bool,
    pub relay_all_peer_rpc: bool,
    pub disable_relay_kcp: bool,
    pub enable_relay_foreign_network_kcp: bool,
    pub private_mode: bool,
    pub dev_name: String,
    pub default_protocol: String,
    pub ipv6: String,
    pub foreign_relay_bps_limit: u64,
    pub relay_network_whitelist: Vec<String>,
    pub manual_routes: Vec<String>,
    pub mapped_listeners: Vec<String>,
    pub tcp_whitelist: Vec<String>,
    pub udp_whitelist: Vec<String>,
    pub stun_servers: Vec<String>,
    pub stun_servers_v6: Vec<String>,
    pub listener_port: u16,
    pub ipv4: String,
    pub mtu: u32,
    pub multi_thread: bool,
    pub thread_count: u32,
    pub latency_first: bool,
    pub bind_device: bool,
    pub compression: String,
    pub p2p_mode: String,
    pub udp_hole_punching: bool,
    pub tcp_hole_punching: bool,
    pub symmetric_hole_punching: bool,
    pub kcp: bool,
    pub quic: bool,
    pub quic_port: u16,
    pub disable_ipv6: bool,
    pub exit_nodes: Vec<String>,
    pub proxy_networks: Vec<String>,
    pub port_forwards: Vec<Forward>,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Forward {
    pub protocol: String,
    pub local_port: u16,
    pub target_ip: String,
    pub target_port: u16,
}

impl Default for NetworkSettings {
    fn default() -> Self {
        Self {
            use_smoltcp: false, disable_kcp_input: false, disable_quic_input: false,
            proxy_forward_by_system: false, enable_as_exit_node: false,
            relay_all_peer_rpc: false, disable_relay_kcp: false,
            enable_relay_foreign_network_kcp: false, private_mode: false,
            dev_name: "MCTier_Net".into(), default_protocol: String::new(), ipv6: String::new(),
            foreign_relay_bps_limit: 0, relay_network_whitelist: vec![], manual_routes: vec![],
            mapped_listeners: vec![], tcp_whitelist: vec![], udp_whitelist: vec![],
            stun_servers: vec![], stun_servers_v6: vec![],
            listener_port: 0,
            ipv4: String::new(),
            mtu: 1360,
            multi_thread: true,
            thread_count: 2,
            latency_first: true,
            bind_device: false,
            compression: "none".into(),
            p2p_mode: "auto".into(),
            udp_hole_punching: true,
            tcp_hole_punching: true,
            symmetric_hole_punching: true,
            kcp: false,
            quic: false,
            quic_port: 0,
            disable_ipv6: false,
            exit_nodes: vec![],
            proxy_networks: vec![],
            port_forwards: vec![],
        }
    }
}
pub fn safe_port(port: u16) -> bool {
    port >= 1024 && !matches!(port, 14700 | 14539 | 14540)
}
impl NetworkSettings {
    fn validate_extended(&self) -> Result<(), String> {
        if self.dev_name.len() > 15 || (!self.dev_name.is_empty() &&
            (!self.dev_name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
             || self.dev_name.starts_with('-'))) {
            return Err("TUN 名称限15个字母、数字、下划线或连字符，不能以连字符开头".into());
        }
        if !matches!(self.default_protocol.as_str(), "" | "udp" | "tcp" | "ws" | "wss" | "wg") {
            return Err("默认协议须为 udp/tcp/ws/wss/wg 或留空".into());
        }
        if self.foreign_relay_bps_limit > 9_007_199_254_740_991 {
            return Err("中继限速超出浏览器整数范围".into());
        }
        if !self.ipv6.is_empty() {
            let (ip, prefix) = self.ipv6.split_once('/').ok_or("IPv6 需要地址/前缀")?;
            let ip: std::net::Ipv6Addr = ip.parse().map_err(|_| "IPv6 地址无效")?;
            let prefix: u8 = prefix.parse().map_err(|_| "IPv6 前缀无效")?;
            if prefix > 128 || ip.is_unspecified() || ip.is_loopback() || ip.is_multicast() || self.disable_ipv6 {
                return Err("虚拟 IPv6 不得为回环、未指定或组播；启用时不能同时禁用 IPv6".into());
            }
        }
        for list in [&self.relay_network_whitelist, &self.manual_routes, &self.mapped_listeners,
            &self.tcp_whitelist, &self.udp_whitelist, &self.stun_servers, &self.stun_servers_v6] {
            if list.len() > 16 || list.iter().any(|v| v.is_empty() || v.len() > 253 || v.starts_with('-') || v.chars().any(|c| c.is_control() || c.is_whitespace())) {
                return Err("高级列表最多16项；每项限253字符，不接受空白、控制字符或命令参数".into());
            }
        }
        if self.relay_network_whitelist.iter().any(|v| !v.chars().all(|c| c.is_alphanumeric() || "_-.*?".contains(c))) {
            return Err("中继网络白名单须为网络名称或通配符".into());
        }
        for route in &self.manual_routes {
            let (ip, prefix) = route.split_once('/').ok_or("路由需要 IPv4 CIDR")?;
            let _: std::net::Ipv4Addr = ip.parse().map_err(|_| "路由 IPv4 无效")?;
            let prefix: u8 = prefix.parse().map_err(|_| "路由前缀无效")?;
            if prefix > 32 { return Err("路由前缀须为0–32".into()); }
        }
        for list in [&self.tcp_whitelist, &self.udp_whitelist] {
            for value in list {
                let (a, b) = value.split_once('-').unwrap_or((value, value));
                let a: u16 = a.parse().map_err(|_| "白名单须为端口或端口范围")?;
                let b: u16 = b.parse().map_err(|_| "白名单须为端口或端口范围")?;
                if a == 0 || a > b { return Err("端口白名单范围无效".into()); }
            }
        }
        for v in &self.mapped_listeners {
            let u = reqwest::Url::parse(v).map_err(|_| "映射监听器 URL 无效")?;
            if !matches!(u.scheme(), "tcp" | "udp" | "ws" | "wss" | "wg") || u.host_str().is_none()
                || u.port().is_none_or(|p| p == 0) || !u.username().is_empty() || u.password().is_some()
                || u.query().is_some() || u.fragment().is_some() || !matches!(u.path(), "" | "/") {
                return Err("映射监听器须为协议://地址:端口，不含凭证、路径或查询".into());
            }
        }
        for v in self.stun_servers.iter().chain(&self.stun_servers_v6) {
            let u = reqwest::Url::parse(&format!("udp://{v}")).map_err(|_| "STUN 地址须为主机:端口，IPv6 使用[地址]:端口")?;
            if u.host_str().is_none() || u.port().is_none_or(|p| p == 0) || !u.username().is_empty()
                || u.password().is_some() || !u.path().is_empty() || u.query().is_some() || u.fragment().is_some() {
                return Err("STUN 地址须为主机:端口，IPv6 使用[地址]:端口".into());
            }
        }
        Ok(())
    }
    pub fn config(&self) -> Result<EasyTierAdvancedConfig, String> {
        if (self.listener_port != 0 && !safe_port(self.listener_port))
            || !(576..=9000).contains(&self.mtu)
            || !(2..=32).contains(&self.thread_count)
            || !matches!(self.compression.as_str(), "none" | "zstd")
            || !matches!(self.p2p_mode.as_str(), "auto" | "direct" | "relay")
            || (self.quic && (!safe_port(self.quic_port) || self.quic_port == self.listener_port))
        {
            return Err(
                "网络参数无效：MTU 576–9000、线程 2–32；QUIC 需要独立固定端口；保留端口不能使用"
                    .into(),
            );
        }
        if !self.ipv4.is_empty() && virtual_host(&self.ipv4).is_none() {
            return Err("手动地址必须为 10.126.126.1–254，不带掩码".into());
        }
        if self.exit_nodes.len() > 8
            || self.proxy_networks.len() > 16
            || self.port_forwards.len() > 16
        {
            return Err("最多 8 个出口节点、16 个子网或转发规则".into());
        }
        if self.exit_nodes.iter().any(|ip| virtual_host(ip).is_none()) {
            return Err("出口节点必须使用虚拟 IPv4 地址".into());
        }
        // Do not accept arbitrary strings as flags or DNS names.
        for cidr in &self.proxy_networks {
            let (ip, prefix) = cidr.split_once('/').ok_or("子网需要 IPv4 CIDR")?;
            let ip: std::net::Ipv4Addr = ip.parse().map_err(|_| "子网 IPv4 无效")?;
            let prefix: u8 = prefix.parse().map_err(|_| "子网掩码无效")?;
            let oct = ip.octets();
            let private = oct[0] == 10
                || (oct[0] == 172 && (16..=31).contains(&oct[1]))
                || (oct[0] == 192 && oct[1] == 168);
            if !private || !(8..=32).contains(&prefix) || (oct[..3] == [10, 126, 126]) {
                return Err("仅允许明确的 RFC1918 私有子网，不可代理 MCTier 自身网段".into());
            }
            let mask = u32::MAX << (32 - prefix);
            let start = u32::from(ip) & mask;
            let end = start | !mask;
            let (private_start, private_end) = if oct[0] == 10 {
                (0x0a000000, 0x0affffff)
            } else if oct[0] == 172 {
                (0xac100000, 0xac1fffff)
            } else {
                (0xc0a80000, 0xc0a8ffff)
            };
            if start < private_start || end > private_end {
                return Err("整个 CIDR 必须位于私有网段内".into());
            }
            let overlay = u32::from(std::net::Ipv4Addr::new(10, 126, 126, 1));
            if start <= overlay && overlay <= end {
                return Err("代理子网不能覆盖 MCTier 网段".into());
            }
        }
        let mut used = std::collections::BTreeSet::new();
        for f in &self.port_forwards {
            if !matches!(f.protocol.as_str(), "tcp" | "udp")
                || !safe_port(f.local_port)
                || !safe_port(f.target_port)
                || virtual_host(&f.target_ip).is_none()
                || f.local_port == self.listener_port
                || (self.quic && f.local_port == self.quic_port)
                || !used.insert((f.protocol.clone(), f.local_port))
            {
                return Err(
                    "转发须为 tcp/udp、非保留端口和虚拟目标地址，且不能重复或占用监听端口".into(),
                );
            }
        }
        self.validate_extended()?;
        let mut c = EasyTierAdvancedConfig::default();
        c.use_smoltcp = self.use_smoltcp;
        c.disable_kcp_input = self.disable_kcp_input;
        c.disable_quic_input = self.disable_quic_input;
        c.proxy_forward_by_system = self.proxy_forward_by_system;
        c.enable_as_exit_node = self.enable_as_exit_node;
        c.relay_all_peer_rpc = self.relay_all_peer_rpc;
        c.disable_relay_kcp = self.disable_relay_kcp;
        c.enable_relay_foreign_network_kcp = self.enable_relay_foreign_network_kcp;
        c.private_mode = self.private_mode;
        c.dev_name = (!self.dev_name.is_empty()).then(|| self.dev_name.clone());
        c.default_protocol = (!self.default_protocol.is_empty()).then(|| self.default_protocol.clone());
        c.ipv6 = (!self.ipv6.is_empty()).then(|| self.ipv6.clone());
        c.foreign_relay_bps_limit = (self.foreign_relay_bps_limit != 0).then_some(self.foreign_relay_bps_limit);
        c.relay_network_whitelist = self.relay_network_whitelist.clone();
        c.manual_routes = self.manual_routes.clone();
        c.mapped_listeners = self.mapped_listeners.clone();
        c.tcp_whitelist = self.tcp_whitelist.clone();
        c.udp_whitelist = self.udp_whitelist.clone();
        c.stun_servers = self.stun_servers.clone();
        c.stun_servers_v6 = self.stun_servers_v6.clone();
        c.ipv4 = (!self.ipv4.is_empty()).then(|| format!("{}/24", self.ipv4));
        c.mtu = Some(self.mtu);
        c.multi_thread = self.multi_thread;
        c.multi_thread_count = Some(self.thread_count);
        c.latency_first = self.latency_first;
        c.bind_device = self.bind_device;
        c.compression = Some(self.compression.clone());
        c.p2p_only = self.p2p_mode == "direct";
        c.disable_p2p = self.p2p_mode == "relay";
        c.disable_udp_hole_punching = !self.udp_hole_punching;
        c.disable_tcp_hole_punching = !self.tcp_hole_punching;
        c.disable_sym_hole_punching = !self.symmetric_hole_punching;
        c.enable_kcp_proxy = self.kcp;
        c.enable_quic_proxy = self.quic;
        c.quic_listen_port = self.quic.then_some(self.quic_port);
        c.disable_ipv6 = self.disable_ipv6;
        c.exit_nodes = self.exit_nodes.clone();
        c.proxy_networks = self.proxy_networks.clone();
        c.port_forward_rules = self
            .port_forwards
            .iter()
            .map(|f| PortForwardRule {
                protocol: f.protocol.clone(),
                bind_addr: format!("127.0.0.1:{}", f.local_port),
                dst_addr: format!("{}:{}", f.target_ip, f.target_port),
            })
            .collect();
        // Linux Web does not manage system DNS.
        c.accept_dns = false;
        Ok(c)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_unsafe_parameters_and_routes() {
        assert!(NetworkSettings::default().config().is_ok());
        for port in [80, 14700, 14540] {
            let s = NetworkSettings {
                listener_port: port,
                ..Default::default()
            };
            assert!(s.config().is_err());
        }
        for cidr in [
            "0.0.0.0/0",
            "10.0.0.0/8",
            "127.0.0.1/32",
            "10.126.126.0/24",
            "--peers",
            "192.168.0.0/8",
        ] {
            assert!(
                NetworkSettings {
                    proxy_networks: vec![cidr.into()],
                    ..Default::default()
                }
                .config()
                .is_err(),
                "{cidr}"
            );
        }
        let s = NetworkSettings {
            port_forwards: vec![Forward {
                protocol: "tcp".into(),
                local_port: 25566,
                target_ip: "10.126.126.3".into(),
                target_port: 25565,
            }],
            ..Default::default()
        };
        let c = s.config().unwrap();
        assert_eq!(c.port_forward_rules[0].bind_addr, "127.0.0.1:25566");
        assert!(!c.accept_dns);
        assert!(
            serde_json::from_value::<NetworkSettings>(serde_json::json!({"noTun":true})).is_err()
        );
    }
}
