//! A bounded Linux Web UI adapter for the original EasyTier config/argv logic.
use crate::modules::{
    config_manager::{EasyTierAdvancedConfig, PortForwardRule},
    virtual_network::virtual_host,
};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase", deny_unknown_fields)]
pub struct NetworkSettings {
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
        let mut c = EasyTierAdvancedConfig::default();
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
        // Magic DNS uses the original bounded hosts helper, never EasyTier's OS DNS takeover.
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
