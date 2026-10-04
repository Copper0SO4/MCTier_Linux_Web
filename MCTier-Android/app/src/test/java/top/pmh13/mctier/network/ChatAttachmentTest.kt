package top.pmh13.mctier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.pmh13.mctier.data.ChatAttachmentMeta
import top.pmh13.mctier.data.ChatMaxAttachmentBytes
import top.pmh13.mctier.data.chatAttachmentKind
import top.pmh13.mctier.data.chatAttachmentMime
import top.pmh13.mctier.data.validChatAttachment

class ChatAttachmentTest {
    private val valid = ChatAttachmentMeta("att-123456789abc", "meeting.mp3", "audio/mpeg", 4096)

    @Test fun validatesMetadataAndRejectsTraversalOrOversize() {
        assertTrue(validChatAttachment(valid))
        assertFalse(validChatAttachment(valid.copy(name = "../secret.txt")))
        assertFalse(validChatAttachment(valid.copy(size = ChatMaxAttachmentBytes.toLong() + 1)))
        assertFalse(validChatAttachment(valid.copy(mime = "audio/mpeg; charset=utf-8")))
    }

    @Test fun detectsCommonKindsAndMimeTypes() {
        assertEquals("audio", chatAttachmentKind(valid))
        assertEquals("image", chatAttachmentKind(valid.copy(name = "photo.png", mime = "image/png")))
        assertEquals("video", chatAttachmentKind(valid.copy(name = "clip.mp4", mime = "video/mp4")))
        assertEquals("word", chatAttachmentKind(valid.copy(name = "notes.docx", mime = "application/octet-stream")))
        assertEquals("sheet", chatAttachmentKind(valid.copy(name = "table.xlsx", mime = "application/octet-stream")))
        assertEquals("sheet", chatAttachmentKind(valid.copy(name = "table.xls", mime = "application/octet-stream")))
        assertEquals("sheet", chatAttachmentKind(valid.copy(name = "table.ods", mime = "application/octet-stream")))
        assertEquals("sheet", chatAttachmentKind(valid.copy(name = "table.csv", mime = "application/octet-stream")))
        assertEquals("slides", chatAttachmentKind(valid.copy(name = "deck.pptx", mime = "application/octet-stream")))
        assertEquals("slides", chatAttachmentKind(valid.copy(name = "deck.ppt", mime = "application/octet-stream")))
        assertEquals("slides", chatAttachmentKind(valid.copy(name = "deck.odp", mime = "application/octet-stream")))
        assertEquals("word", chatAttachmentKind(valid.copy(name = "notes.doc", mime = "application/octet-stream")))
        assertEquals("word", chatAttachmentKind(valid.copy(name = "notes.odt", mime = "application/octet-stream")))
        assertEquals("application/pdf", chatAttachmentMime("report.pdf"))
    }
}
