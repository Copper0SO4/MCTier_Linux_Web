package top.pmh13.mctier.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLinkTest {
    @Test
    fun acceptsHttpAndHttpsLinksWithHosts() {
        assertTrue(isSafeChatUrl("http://example.com/path?q=1"))
        assertTrue(isSafeChatUrl("https://example.com/#section"))
    }

    @Test
    fun rejectsNonWebSchemesCredentialsAndMalformedHosts() {
        assertFalse(isSafeChatUrl("javascript:alert(1)"))
        assertFalse(isSafeChatUrl("file:///etc/passwd"))
        assertFalse(isSafeChatUrl("https://user:password@example.com"))
        assertFalse(isSafeChatUrl("https:///missing-host"))
        assertFalse(isSafeChatUrl("https://example.com\nhttps://evil.example"))
    }
}
