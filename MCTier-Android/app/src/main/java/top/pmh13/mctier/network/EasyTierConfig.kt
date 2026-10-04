package top.pmh13.mctier.network

internal fun buildEasyTierConfig(
    instanceName: String,
    networkName: String,
    networkSecret: String,
    hostname: String,
    peers: List<String>,
    virtualIp: String,
    mtu: Int = 1420,
    latencyFirst: Boolean = true,
    proxyCidrs: List<String> = emptyList(),
    exitNodes: List<String> = emptyList(),
    asExitNode: Boolean = false,
    multiThread: Boolean = true,
    useSmoltcp: Boolean = false,
    enableKcpProxy: Boolean = false,
    enableQuicProxy: Boolean = false,
    disableP2p: Boolean = false,
    disableUdpHolePunching: Boolean = false,
    relayAllPeerRpc: Boolean = false,
    compressionZstd: Boolean = false,
    privateMode: Boolean = false,
    acceptDns: Boolean = false,
): String {
    // 【总根源修复】此前使用了错误的 TOML 字段名（inst_name / network / network_secret 顶层 /
    // peers=[字符串]），EasyTier(TomlConfigLoader) 会静默忽略这些未知字段，导致手机端
    // 网络标识为空、且没有任何 peer —— EasyTier 实际从未加入正确网络、也没连任何中继，
    // 因此与电脑端完全不通（语音/聊天/屏幕全废）。
    // 这里严格按 EasyTier Config 结构体的字段名生成：instance_name、[network_identity]、
    // [[peer]] uri、[flags] 等，确保手机端真正加入 "MCTier-xxx" 网络并连接全部冗余中继。
    fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    val sb = StringBuilder()
    // —— 顶层标量字段（必须位于所有 [table] 之前，否则会被解析成上一个表的字段）——
    sb.append("instance_name = \"").append(esc(instanceName)).append("\"\n")
    sb.append("hostname = \"").append(esc(hostname)).append("\"\n")
    sb.append("ipv4 = \"").append(virtualIp).append("\"\n")
    sb.append("dhcp = false\n")
    if (exitNodes.isNotEmpty()) {
        sb.append("exit_nodes = [")
        sb.append(exitNodes.joinToString(", ") { "\"$it\"" })
        sb.append("]\n")
    }
    // —— 网络标识（决定加入哪个网络，必须与桌面端完全一致）——
    sb.append("\n[network_identity]\n")
    sb.append("network_name = \"").append(esc(networkName)).append("\"\n")
    sb.append("network_secret = \"").append(esc(networkSecret)).append("\"\n")
    // —— 用户选择的对端中继节点 ——
    peers.forEach { p ->
        sb.append("\n[[peer]]\nuri = \"").append(p).append("\"\n")
    }
    // —— 代理网段（可选）——
    if (proxyCidrs.isNotEmpty()) {
        proxyCidrs.forEach { cidr ->
            sb.append("\n[[proxy_network]]\ncidr = \"").append(cidr).append("\"\n")
        }
    }
    // —— flags：性能与出口节点开关 ——
    sb.append("\n[flags]\n")
    sb.append("disable_encryption = false\nencryption_algorithm = \"aes-256-gcm\"\n")
    sb.append("latency_first = ").append(latencyFirst).append("\n")
    sb.append("mtu = ").append(mtu).append("\n")
    sb.append("multi_thread = ").append(multiThread).append("\n")
    sb.append("enable_kcp_proxy = ").append(enableKcpProxy).append("\n")
    sb.append("enable_quic_proxy = ").append(enableQuicProxy).append("\n")
    sb.append("disable_p2p = ").append(disableP2p).append("\n")
    sb.append("disable_udp_hole_punching = ").append(disableUdpHolePunching).append("\n")
    sb.append("relay_all_peer_rpc = ").append(relayAllPeerRpc).append("\n")
    sb.append("private_mode = ").append(privateMode).append("\n")
    if (useSmoltcp) sb.append("use_smoltcp = true\n")
    if (acceptDns) {
        // 启用 EasyTier Magic DNS，并把域设为 mct.net.（与桌面端虚拟域名 <玩家名>.mct.net 一致）
        sb.append("accept_dns = true\n")
        sb.append("tld_dns_zone = \"mct.net.\"\n")
    }
    if (asExitNode) {
        sb.append("enable_exit_node = true\n")
    }
    return sb.toString()
}
