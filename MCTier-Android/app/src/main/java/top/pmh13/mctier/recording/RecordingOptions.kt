package top.pmh13.mctier.recording

data class RecordingOptions(
    val resolution: Int = 1080, val fps: Int = 60, val bitrate: Int = 16,
    val systemAudio: Boolean = false, val microphone: Boolean = false,
) {
    fun validate() {
        require(resolution in listOf(720, 1080, 1440, 2160) && fps in listOf(30, 60) && bitrate in listOf(4, 8, 16, 32)) { "无效录屏画质" }
    }
    fun size(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, resolution.toDouble() / minOf(width, height).coerceAtLeast(1), resolution * 16.0 / 9 / maxOf(width, height).coerceAtLeast(1))
        return (maxOf(2, (width * scale).toInt() / 2 * 2) to maxOf(2, (height * scale).toInt() / 2 * 2))
    }
}

internal fun mixRecordingPcm(a: ShortArray, b: ShortArray?, count: Int): ShortArray =
    ShortArray(count) { if (b == null) a[it] else ((a[it].toInt() + b[it].toInt()) / 2).toShort() }

data class RecordingState(
    val phase: String = "idle", val seconds: Long = 0, val uri: String = "", val error: String = "",
)
