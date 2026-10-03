//! EasyTier argument mapping shared by desktop and headless Linux.
pub fn apply_advanced_config(
    cmd: &mut tokio::process::Command,
    config: &crate::modules::config_manager::EasyTierAdvancedConfig,
) {
    log::info!("应用 EasyTier 高级配置");

    // ========== 网络模式 ==========
    if config.no_tun {
        cmd.arg("--no-tun");
        log::info!("  ✅ 启用无 TUN 模式");
    }

    if config.dhcp {
        cmd.arg("--dhcp").arg("true");
        log::info!("  ✅ 启用 DHCP");
    } else {
        cmd.arg("--dhcp").arg("false");
    }

    if let Some(ref ipv4) = config.ipv4 {
        if !ipv4.is_empty() {
            cmd.arg("--ipv4").arg(ipv4);
            log::info!("  ✅ 手动指定 IPv4: {}", ipv4);
        }
    }

    // ========== 代理和转发 ==========
    if config.enable_socks5 {
        if let Some(port) = config.socks5_port {
            cmd.arg("--socks5").arg(port.to_string());
            log::info!("  ✅ 启用 SOCKS5 代理，端口: {}", port);
        }
    }

    for rule in &config.port_forward_rules {
        let forward_rule = format!("{}://{}/{}", rule.protocol, rule.bind_addr, rule.dst_addr);
        cmd.arg("--port-forward").arg(&forward_rule);
        log::info!("  ✅ 添加端口转发规则: {}", forward_rule);
    }

    if config.proxy_forward_by_system {
        cmd.arg("--proxy-forward-by-system");
        log::info!("  ✅ 启用系统转发");
    }

    for network in &config.proxy_networks {
        if !network.trim().is_empty() {
            cmd.arg("--proxy-networks").arg(network.trim());
            log::info!("  ✅ 添加代理网络: {}", network.trim());
        }
    }

    // ========== 出口节点 ==========
    if config.enable_as_exit_node {
        cmd.arg("--enable-exit-node");
        log::info!("  ✅ 启用作为出口节点");
    }

    for node in &config.exit_nodes {
        if !node.trim().is_empty() {
            cmd.arg("--exit-nodes").arg(node.trim());
            log::info!("  ✅ 使用出口节点: {}", node.trim());
        }
    }

    // ========== 性能优化 ==========
    if config.multi_thread {
        cmd.arg("--multi-thread").arg("true");
        if let Some(count) = config.multi_thread_count {
            if count >= 2 {
                cmd.arg("--multi-thread-count").arg(count.to_string());
                log::info!("  ✅ 启用多线程，线程数: {}", count);
            }
        } else {
            log::info!("  ✅ 启用多线程（默认2线程）");
        }
    }

    if config.latency_first {
        cmd.arg("--latency-first").arg("true");
        log::info!("  ✅ 启用延迟优先模式");
    }

    if config.use_smoltcp {
        cmd.arg("--use-smoltcp");
        log::info!("  ✅ 启用 smoltcp");
    }

    // ========== 协议优化 ==========
    if config.enable_kcp_proxy {
        cmd.arg("--enable-kcp-proxy");
        log::info!("  ✅ 启用 KCP 代理");
    }

    if config.disable_kcp_input {
        cmd.arg("--disable-kcp-input");
        log::info!("  ✅ 禁用 KCP 输入");
    }

    if config.enable_quic_proxy {
        cmd.arg("--enable-quic-proxy");
        log::info!("  ✅ 启用 QUIC 代理");
    }

    if config.disable_quic_input {
        cmd.arg("--disable-quic-input");
        log::info!("  ✅ 禁用 QUIC 输入");
    }

    if let Some(port) = config.quic_listen_port {
        cmd.arg("--quic-listen-port").arg(port.to_string());
        log::info!("  ✅ QUIC 监听端口: {}", port);
    }

    // ========== 加密和安全 ==========
    // All application traffic (including media and private messages) uses
    // the overlay. Never allow a saved legacy setting to disable encryption.
    cmd.arg("--encryption-algorithm").arg("aes-256-gcm");

    // ========== 网络设备 ==========
    if config.bind_device {
        cmd.arg("--bind-device").arg("true");
        log::info!("  ✅ 绑定到物理设备");
    }

    if let Some(ref dev_name) = config.dev_name {
        if !dev_name.is_empty() {
            cmd.arg("--dev-name").arg(dev_name);
            log::info!("  ✅ TUN 设备名称: {}", dev_name);
        }
    }

    if let Some(mtu) = config.mtu {
        cmd.arg("--mtu").arg(mtu.to_string());
        log::info!("  ✅ MTU: {}", mtu);
    }

    // ========== P2P 配置 ==========
    if config.p2p_only {
        cmd.arg("--p2p-only");
        log::info!("  ✅ 仅使用 P2P");
    }

    if config.disable_p2p {
        cmd.arg("--disable-p2p");
        log::info!("  ✅ 禁用 P2P");
    }

    if config.disable_udp_hole_punching {
        cmd.arg("--disable-udp-hole-punching");
        log::info!("  ✅ 禁用 UDP 打洞");
    }

    if config.disable_tcp_hole_punching {
        cmd.arg("--disable-tcp-hole-punching");
        log::info!("  ✅ 禁用 TCP 打洞");
    }

    if config.disable_sym_hole_punching {
        cmd.arg("--disable-sym-hole-punching");
        log::info!("  ✅ 禁用对称 NAT 打洞");
    }

    // ========== 中继配置 ==========
    for network in &config.relay_network_whitelist {
        if !network.trim().is_empty() {
            cmd.arg("--relay-network-whitelist").arg(network.trim());
            log::info!("  ✅ 中继网络白名单: {}", network.trim());
        }
    }

    if config.relay_all_peer_rpc {
        cmd.arg("--relay-all-peer-rpc");
        log::info!("  ✅ 转发所有对等节点 RPC");
    }

    if config.disable_relay_kcp {
        cmd.arg("--disable-relay-kcp");
        log::info!("  ✅ 禁用中继 KCP");
    }

    if config.enable_relay_foreign_network_kcp {
        cmd.arg("--enable-relay-foreign-network-kcp");
        log::info!("  ✅ 启用中继外部网络 KCP");
    }

    if let Some(limit) = config.foreign_relay_bps_limit {
        cmd.arg("--foreign-relay-bps-limit").arg(limit.to_string());
        log::info!("  ✅ 外部网络流量限制: {} BPS", limit);
    }

    // ========== 路由配置 ==========
    for route in &config.manual_routes {
        if !route.trim().is_empty() {
            cmd.arg("--manual-routes").arg(route.trim());
            log::info!("  ✅ 手动路由: {}", route.trim());
        }
    }

    // ========== 压缩 ==========
    if let Some(ref compression) = config.compression {
        if !compression.is_empty() {
            cmd.arg("--compression").arg(compression);
            log::info!("  ✅ 压缩算法: {}", compression);
        }
    }

    // ========== 监听器配置 ==========
    for listener in &config.listeners {
        if !listener.trim().is_empty() {
            cmd.arg("--listeners").arg(listener.trim());
            log::info!("  ✅ 监听器: {}", listener.trim());
        }
    }

    for mapped in &config.mapped_listeners {
        if !mapped.trim().is_empty() {
            cmd.arg("--mapped-listeners").arg(mapped.trim());
            log::info!("  ✅ 映射监听器: {}", mapped.trim());
        }
    }

    if config.no_listener {
        cmd.arg("--no-listener");
        log::info!("  ✅ 不监听任何端口");
    }

    if let Some(ref protocol) = config.default_protocol {
        if !protocol.is_empty() {
            cmd.arg("--default-protocol").arg(protocol);
            log::info!("  ✅ 默认协议: {}", protocol);
        }
    }

    // ========== DNS 配置 ==========
    if config.accept_dns {
        // 当前 easytier-core 要求 --accept-dns 必须带布尔值
        cmd.arg("--accept-dns").arg("true");
        log::info!("  ✅ 启用魔法 DNS");
    }

    if let Some(ref zone) = config.tld_dns_zone {
        if !zone.is_empty() {
            cmd.arg("--tld-dns-zone").arg(zone);
            log::info!("  ✅ 顶级域名区域: {}", zone);
        }
    }

    // ========== 端口白名单 ==========
    for port in &config.tcp_whitelist {
        if !port.trim().is_empty() {
            cmd.arg("--tcp-whitelist").arg(port.trim());
            log::info!("  ✅ TCP 端口白名单: {}", port.trim());
        }
    }

    for port in &config.udp_whitelist {
        if !port.trim().is_empty() {
            cmd.arg("--udp-whitelist").arg(port.trim());
            log::info!("  ✅ UDP 端口白名单: {}", port.trim());
        }
    }

    // ========== IPv6 ==========
    if config.disable_ipv6 {
        cmd.arg("--disable-ipv6");
        log::info!("  ✅ 禁用 IPv6");
    }

    if let Some(ref ipv6) = config.ipv6 {
        if !ipv6.is_empty() {
            cmd.arg("--ipv6").arg(ipv6);
            log::info!("  ✅ IPv6 地址: {}", ipv6);
        }
    }

    // ========== STUN 服务器 ==========
    for server in &config.stun_servers {
        if !server.trim().is_empty() {
            cmd.arg("--stun-servers").arg(server.trim());
            log::info!("  ✅ STUN 服务器: {}", server.trim());
        }
    }

    for server in &config.stun_servers_v6 {
        if !server.trim().is_empty() {
            cmd.arg("--stun-servers-v6").arg(server.trim());
            log::info!("  ✅ IPv6 STUN 服务器: {}", server.trim());
        }
    }

    // ========== 私有模式 ==========
    if config.private_mode {
        // Unlike optional boolean switches, EasyTier requires an explicit value here.
        cmd.arg("--private-mode").arg("true");
        log::info!("  ✅ 启用私有模式");
    }

    log::info!("EasyTier 高级配置应用完成");
}
