package top.pmh13.mctier.ui

/** Window opacity, not child-view alpha, controls Android untrusted-touch blocking. */
internal object DanmakuTouchPolicy {
    fun opacity(requested: Float, systemLimit: Float): Float {
        val limit = if (systemLimit.isFinite()) systemLimit.coerceIn(0f, 1f) else 0.8f
        return if (requested.isFinite()) requested.coerceIn(0f, limit) else minOf(0.8f, limit)
    }
}
