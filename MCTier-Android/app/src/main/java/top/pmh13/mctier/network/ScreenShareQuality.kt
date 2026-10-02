package top.pmh13.mctier.network

data class ScreenShareQuality(
    val resolution: Int = 1080,
    val frameRate: Int = 30,
    val bitrateMbps: Int = 0,
) {
    fun normalized() = ScreenShareQuality(
        resolution.takeIf { it in resolutions } ?: 1080,
        frameRate.takeIf { it in frameRates } ?: 30,
        bitrateMbps.takeIf { it in bitrates } ?: 0,
    )

    fun maxBitrate(): Int {
        val q = normalized()
        val base = mapOf(720 to 2, 1080 to 4, 1440 to 8, 2160 to 16).getValue(q.resolution)
        return (if (q.bitrateMbps > 0) q.bitrateMbps else (base * q.frameRate / 30).coerceAtMost(64)) * 1_000_000
    }

    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val short = normalized().resolution
        val long = short * 16 / 9
        if (width <= 0 || height <= 0) return long to short
        val scale = minOf(1.0, short.toDouble() / minOf(width, height), long.toDouble() / maxOf(width, height))
        fun even(n: Double) = (n.toInt() / 2 * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    companion object {
        val resolutions = listOf(720, 1080, 1440, 2160)
        val frameRates = listOf(30, 60, 120)
        val bitrates = listOf(0, 4, 8, 16, 32, 64)
    }
}
