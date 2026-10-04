package top.pmh13.mctier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.pmh13.mctier.data.sniffChatImageMime
import top.pmh13.mctier.data.ChatImageMaxBytes

class ImageFormatTest {
    @Test
    fun inlineImageLimitMatchesTheEncryptedChatBoundary() {
        assertEquals(2 * 1024 * 1024, ChatImageMaxBytes)
    }

    @Test
    fun detectsSupportedImagesFromMagicBytes() {
        assertEquals("image/gif", sniffChatImageMime("GIF89a".toByteArray()))
        assertEquals("image/png", sniffChatImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)))
        assertEquals("image/jpeg", sniffChatImageMime(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals("image/webp", sniffChatImageMime(byteArrayOf(82, 73, 70, 70, 0, 0, 0, 0, 87, 69, 66, 80)))
        assertNull(sniffChatImageMime("<svg".toByteArray()))
    }
}
