package top.pmh13.mctier.network

import org.junit.Assert.*
import org.junit.Test

class ScreenShareQualityTest {
    @Test fun dimensionsPreserveAspectRatioOrientationAndOriginalSize() {
        val quality = ScreenShareQuality()
        assertEquals(1920 to 1080, quality.dimensions(3840, 2160))
        assertEquals(1080 to 1920, quality.dimensions(2160, 3840))
        assertEquals(1280 to 720, ScreenShareQuality(2160).dimensions(1280, 720))
        assertEquals(1920 to 810, quality.dimensions(2560, 1080))
        assertEquals(1280 to 720, ScreenShareQuality(720).dimensions(0, 0))
    }

    @Test fun profilesHaveBoundedBitratesAndDoNotClampHighFrameRates() {
        for (resolution in ScreenShareQuality.resolutions) for (fps in ScreenShareQuality.frameRates) {
            val quality = ScreenShareQuality(resolution, fps)
            assertEquals(fps, quality.normalized().frameRate)
            assertTrue(quality.maxBitrate() in 2_000_000..64_000_000)
        }
        assertEquals(64_000_000, ScreenShareQuality(2160, 120).maxBitrate())
        assertEquals(8_000_000, ScreenShareQuality(2160, 120, 8).maxBitrate())
        assertEquals(ScreenShareQuality(), ScreenShareQuality(-1, 999, -3).normalized())
    }
}
