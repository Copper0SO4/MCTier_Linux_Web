package top.pmh13.mctier.network

import java.security.MessageDigest

class RegistrationRejected(message: String) : Exception(message)

object LobbyAddress {
    const val Conflict = "virtualIp 已被大厅内其他成员使用"

    fun candidate(lobby: String, identity: String, attempt: Int, preferredHost: Int? = null): String {
        require(attempt in 0..253) { "大厅虚拟 IP 地址已耗尽" }
        if (attempt == 0 && preferredHost != null) {
            require(preferredHost in 1..254) { "虚拟 IP 主机位必须是 1 到 254" }
            return "10.126.126.$preferredHost"
        }
        val hash = MessageDigest.getInstance("SHA-256").digest("$identity\n$lobby".toByteArray(Charsets.UTF_8))
        val seed = hash.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        val first = 2 + seed % 253
        return "10.126.126.${1 + (first - 1 + attempt) % 254}"
    }

    suspend fun <T> recover(
        firstAttempt: Int = 0,
        clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        preferredHost: Int? = null,
        connect: suspend (Int) -> T,
    ): T {
        val started = clockMillis()
        for (attempt in firstAttempt..253) {
            try {
                return connect(attempt)
            } catch (error: RegistrationRejected) {
                if (error.message != Conflict) throw error
                if (attempt == 253 || clockMillis() - started >= 60_000) {
                    throw RegistrationRejected("自动分配虚拟 IP 未成功，请稍后重试或检查大厅地址占用")
                }
            }
        }
        throw RegistrationRejected("大厅虚拟 IP 地址已耗尽")
    }
}
