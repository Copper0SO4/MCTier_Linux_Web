package top.pmh13.mctier.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingOptionsTest {
    @Test fun dimensionsPreserveOrientationAndNeverUpscale() {
        for (resolution in listOf(720, 1080, 1440, 2160)) {
            for ((w, h) in listOf(3840 to 2160, 1080 to 2400, 600 to 800, 2560 to 1080)) {
                val (width, height) = RecordingOptions(resolution = resolution).size(w, h)
                assertTrue(width <= w && height <= h)
                assertTrue(width % 2 == 0 && height % 2 == 0)
                assertTrue(kotlin.math.abs(width.toDouble() / height - w.toDouble() / h) < 0.01)
                assertTrue(minOf(width, height) <= resolution)
            }
        }
    }
    @Test fun mixingDoesNotOverflowOrChangeSingleInput() {
        val a = shortArrayOf(32767, -32768, 1200, -1200)
        assertArrayEquals(a, mixRecordingPcm(a, null, 4))
        assertArrayEquals(a, mixRecordingPcm(a, a, 4))
        assertArrayEquals(shortArrayOf(0, 0), mixRecordingPcm(shortArrayOf(32767, -32768), shortArrayOf(-32768, 32767), 2))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsupportedOptions() { RecordingOptions(fps = 1000).validate() }
}
