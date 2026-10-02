package top.pmh13.mctier.network

import org.junit.Assert.*
import org.junit.Test
import top.pmh13.mctier.data.*

class BuiltinEmojiMessageTest {
    @Test fun oldDuplicateMessagesResolveToRetainedEmoji() {
        assertEquals("builtin-1f970", BuiltinEmojiMessage.decode("mctier:emoji:v3:builtin-1f60d"))
        assertEquals("mctier:emoji:v3:builtin-1f970", BuiltinEmojiMessage.encode("builtin-1f60d"))
    }
    @Test fun matchesDesktopWireFormatAndRejectsUntrustedPaths() {
        val text = BuiltinEmojiMessage.encode("builtin-a_B-9")
        assertEquals("mctier:emoji:v3:builtin-a_B-9", text)
        assertEquals("builtin-a_B-9", BuiltinEmojiMessage.decode(text))
        for (id in listOf("", "custom-1", "builtin-", "builtin-../x", "builtin-a/b", "builtin-a\\b", "builtin-%2e", "builtin-😀", "builtin-a\n", "builtin-" + "a".repeat(129))) {
            assertTrue(runCatching { BuiltinEmojiMessage.encode(id) }.isFailure)
            assertNull(BuiltinEmojiMessage.decode("mctier:emoji:v3:$id"))
        }
        for (invalid in listOf(" $text", "$text\r\n", "mctier:emoji:v4:builtin-a", "hello $text", "> quote\n$text")) assertNull(BuiltinEmojiMessage.decode(invalid))
        assertTrue(BuiltinEmojiMessage.encode("builtin-" + "a".repeat(128)).length <= 152)
    }

    @Test fun previewAndRecallDoNotLeakProtocolText() {
        val message = ChatMessage("msg", "peer", "Player", BuiltinEmojiMessage.encode("builtin-a"), 1, type = "text")
        assertEquals(MessagePreview("image", "[内置表情]"), messagePreview(message))
        assertEquals(MessagePreview("text", "[消息已撤回]"), messagePreview(message.copy(recalled = true)))
        assertEquals("hello", messagePreview(message.copy(content = "hello")).text)
    }
}
