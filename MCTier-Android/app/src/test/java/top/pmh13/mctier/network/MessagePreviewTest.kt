package top.pmh13.mctier.network

import org.junit.Assert.*
import org.junit.Test
import top.pmh13.mctier.data.*

class MessagePreviewTest {
    private fun message(type: String, content: String) = ChatMessage("msg", "peer", "Player", content, 1, type = type)

    @Test fun attachmentsNeverDisplayTheirProtocolJson() {
        for ((mime, kind) in listOf("image/gif" to "image", "video/mp4" to "video", "audio/mpeg" to "audio", "application/pdf" to "file")) {
            val meta = ChatAttachmentMeta("att-123456789abc", "sample", mime, 4096)
            val preview = messagePreview(message("file", "{\"id\":\"internal-secret\"}").copy(attachment = meta))
            assertEquals(kind, preview.kind)
            assertEquals("sample", preview.text)
            assertFalse(preview.toString().contains("internal-secret"))
        }
    }

    @Test fun voiceDisplaysDurationWithoutPayload() {
        assertEquals("3 秒", messagePreview(message("voice", "{\"duration\":2.2,\"data\":\"secret\"}")).detail)
        for (content in listOf("bad-json", "{\"duration\":999}", "{\"duration\":-1}")) assertEquals("", messagePreview(message("voice", content)).detail)
        for (type in listOf("file", "voice", "unknown")) assertFalse(messagePreview(message(type, "{\"private\":\"secret\"}")).toString().contains("secret"))
    }

    @Test fun preservesTextAndHandlesLegacyImagesAndRecall() {
        assertEquals("{\"example\":42}", messagePreview(message("text", "{\"example\":42}")).text)
        assertEquals("> hello", messagePreview(message("text", "> [reply:abc] hello")).text)
        assertEquals("image", messagePreview(message("image", "[表情]")).kind)
        assertEquals("[消息已撤回]", messagePreview(message("file", "secret").copy(recalled = true)).text)
    }
}
