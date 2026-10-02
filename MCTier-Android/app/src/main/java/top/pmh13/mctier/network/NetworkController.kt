package top.pmh13.mctier.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.EasyTierVpnService
import kotlinx.coroutines.delay
import top.pmh13.mctier.data.DefaultEasyTierNode
import kotlin.math.absoluteValue

data class NetworkSession(
    val networkName: String,
    val networkKey: String,
    val node: String,
    val virtualIp: String,
)

class NetworkController(private val context: Context) {
    private var currentInstanceName: String? = null

    fun vpnPrepareIntent(): Intent? = VpnService.prepare(context)

    suspend fun startEasyTier(
        lobbyName: String,
        password: String,
        playerName: String,
        node: String = DefaultEasyTierNode,
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
        useDomain: Boolean = false,
        identityId: String,
        addressAttempt: Int = 0,
        preferredVirtualIpHost: Int? = null,
    ): NetworkSession {
        val networkName = "MCTier-$lobbyName"
        val instanceName = "mctier_${lobbyName.hashCode().absoluteValue}_${playerName.hashCode().absoluteValue}"
        val normalizedNode = normalizeNode(node)
        // 只连接用户当前选择的节点，确保节点选择和实际网络连接保持一致。
        val peerList = listOf(normalizedNode)
        val virtualIp = LobbyAddress.candidate(lobbyName, identityId, addressAttempt, preferredVirtualIpHost)
        Log.i(TAG, "Starting EasyTier instance=$instanceName ip=$virtualIp peers=$peerList lobby=$lobbyName")
        if (!EasyTierJNI.available) {
            error("EasyTier native load failed: ${EasyTierJNI.loadErrorMessage ?: "unknown error"}")
        }

        val config = buildEasyTierConfig(
            instanceName = instanceName,
            networkName = networkName,
            networkSecret = password,
            hostname = playerName,
            peers = peerList,
            virtualIp = "$virtualIp/24",
            mtu = mtu,
            latencyFirst = latencyFirst,
            proxyCidrs = proxyCidrs.map { it.trim() }.filter { it.isNotBlank() },
            exitNodes = exitNodes.map { it.trim() }.filter { it.isNotBlank() },
            asExitNode = asExitNode,
            multiThread = multiThread,
            useSmoltcp = useSmoltcp,
            enableKcpProxy = enableKcpProxy,
            enableQuicProxy = enableQuicProxy,
            disableP2p = disableP2p,
            disableUdpHolePunching = disableUdpHolePunching,
            relayAllPeerRpc = relayAllPeerRpc,
            compressionZstd = compressionZstd,
            privateMode = privateMode,
            acceptDns = useDomain,
        )
        val parseResult = EasyTierJNI.parseConfig(config)
        if (parseResult != 0) {
            error(EasyTierJNI.getLastError() ?: "EasyTier config parse failed")
        }
        val runResult = EasyTierJNI.runNetworkInstance(config)
        if (runResult != 0) {
            error(EasyTierJNI.getLastError() ?: "EasyTier start failed")
        }
        currentInstanceName = instanceName
        Log.i(TAG, "EasyTier instance started; starting VPN route=${lobbyRoute(lobbyName)}")
        startVpnService(instanceName, "$virtualIp/24", lobbyRoute(lobbyName), useDomain)
        delay(900)

        val reportedIp = waitForVirtualIp(instanceName)
        if (reportedIp.isNullOrBlank()) {
            stopEasyTier()
            error("EasyTier did not report a virtual IP")
        }
        if (reportedIp.substringBefore('/') != virtualIp) {
            stopEasyTier()
            error("EasyTier virtual IP does not match the VPN address")
        }
        return NetworkSession(networkName, password, normalizedNode, virtualIp)
    }

    suspend fun stopEasyTier() {
        // 【修复 VPN 残留】先停止 EasyTier 网络实例（释放 TUN 文件描述符），
        // 再停止 VpnService，确保系统 VPN 连接被真正关闭、状态栏 VPN 图标消失。
        if (EasyTierJNI.available) {
            runCatching { EasyTierJNI.stopAllInstances() }
        }
        currentInstanceName = null
        // 先发显式 STOP 动作关闭 TUN 与前台服务，再 stopService 兜底，确保 VPN 图标立即消失
        runCatching {
            context.startService(
                Intent(context, EasyTierVpnService::class.java).setAction(EasyTierVpnService.ACTION_STOP),
            )
        }
        runCatching { context.stopService(Intent(context, EasyTierVpnService::class.java)) }
        delay(200)
    }

    private suspend fun waitForVirtualIp(instanceName: String): String? {
        repeat(30) {
            val json = runCatching { EasyTierJNI.collectNetworkInfos(20) }.getOrNull()
            val ip = json?.let { extractVirtualIpv4(it, instanceName) }
            if (!ip.isNullOrBlank()) return ip
            delay(500)
        }
        return null
    }

    private fun startVpnService(instanceName: String, virtualIp: String, route: String, magicDns: Boolean) {
        val intent = Intent(context, EasyTierVpnService::class.java).apply {
            putExtra(EasyTierVpnService.EXTRA_INSTANCE, instanceName)
            putExtra(EasyTierVpnService.EXTRA_IPV4, if (virtualIp.contains("/")) virtualIp else "$virtualIp/24")
            putStringArrayListExtra(EasyTierVpnService.EXTRA_ROUTES, arrayListOf(route))
            putExtra(EasyTierVpnService.EXTRA_MAGIC_DNS, magicDns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // All platforms use the same /24. The VPN and native instance must agree.
    private fun lobbyRoute(lobbyName: String): String = "10.126.126.0/24"

    private fun extractVirtualIpv4(json: String, instanceName: String): String? {
        val instanceIndex = json.indexOf(instanceName)
        if (instanceIndex < 0) return null
        val afterInstance = json.substring(instanceIndex)
        val ipRegex = Regex("""(?:"virtual_ipv4"|"virtualIpv4")[\s\S]{0,160}?"addr"\s*:\s*(-?\d+)[\s\S]{0,80}?(?:"network_length"|"networkLength")\s*:\s*(\d+)""")
        val match = ipRegex.find(afterInstance) ?: return null
        val addr = match.groupValues[1].toLong()
        val prefix = match.groupValues[2].toInt()
        val unsigned = addr and 0xFFFF_FFFFL
        val ip = listOf(
            (unsigned shr 24) and 0xFF,
            (unsigned shr 16) and 0xFF,
            (unsigned shr 8) and 0xFF,
            unsigned and 0xFF,
        ).joinToString(".")
        return "$ip/$prefix"
    }

    private fun normalizeNode(node: String): String {
        val trimmed = node.trim()
        return when (trimmed) {
            "tcp://mctier.pmhs.top:11010",
            "udp://mctier.pmhs.top:11010",
            "ws://test.pmhs.top",
            "wss://mctier.pmhs.top/signaling",
            -> DefaultEasyTierNode
            "tcp://mctiers.pmhs.top" -> "tcp://mctiers.pmhs.top:11010"
            "udp://mctiers.pmhs.top" -> "udp://mctiers.pmhs.top:11010"
            "ws://mctiers.pmhs.top" -> "ws://mctiers.pmhs.top:11011"
            else -> trimmed.ifBlank { DefaultEasyTierNode }
        }
    }

    /**
     * 解析 EasyTier 路由信息，返回 虚拟IP -> 连接类型 映射。
     * EasyTier 路由 cost 字段：1 表示直接相连(P2P 直连)，>1 表示经中继转发。
     * 解析失败时返回空表（UI 显示“未知”）。
     */
    fun peerConnectionTypes(): Map<String, String> {
        if (!EasyTierJNI.available) return emptyMap()
        val json = runCatching { EasyTierJNI.collectNetworkInfos(20) }.getOrNull() ?: return emptyMap()
        val result = HashMap<String, String>()
        // 在 routes 数组里匹配 每条路由的 ipv4 addr 与其后的 cost
        val regex = Regex(""""addr"\s*:\s*(-?\d+)[\s\S]{0,260}?"cost"\s*:\s*(\d+)""")
        regex.findAll(json).forEach { m ->
            val addr = m.groupValues[1].toLongOrNull() ?: return@forEach
            val cost = m.groupValues[2].toIntOrNull() ?: return@forEach
            val unsigned = addr and 0xFFFF_FFFFL
            val ip = listOf(
                (unsigned shr 24) and 0xFF, (unsigned shr 16) and 0xFF,
                (unsigned shr 8) and 0xFF, unsigned and 0xFF,
            ).joinToString(".")
            // 仅记录大厅网段内的对端
            if (ip.startsWith("10.126.")) {
                result[ip] = if (cost <= 1) "p2p" else "relay"
            }
        }
        return result
    }

    private companion object {
        private const val TAG = "NetworkController"
    }
}
