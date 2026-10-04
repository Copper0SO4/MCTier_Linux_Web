package top.pmh13.mctier.network

import org.junit.Assert.*
import org.junit.Test
import top.pmh13.mctier.data.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class PeerPreferencesTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    @Test fun preferencesSurviveRejoinAndDoNotFollowNames() {
        val saved = updatePeerPreference(emptyMap(), alice, PeerPreference(pinned = true, muted = true))
        val restored = Json.decodeFromString<Map<String, PeerPreference>>(Json.encodeToString(saved))
        assertEquals(listOf(alice, bob), sortPrivatePeers(listOf(bob, alice), restored) { it })
        assertNull(restored[bob])
        assertEquals(listOf(bob), sortPrivatePeers(listOf(bob), restored) { it })
        assertTrue(restored[alice]!!.muted)
        assertTrue(updatePeerPreference(restored, alice, PeerPreference()).isEmpty())
    }
    @Test fun unreadIsRetainedWithoutInterruptionsOrDoubleCounting() {
        val unread = mapOf("one" to "private:$alice", "two" to "private:$bob", "public" to "lobby")
        val saved = updatePeerPreference(emptyMap(), alice, PeerPreference(muted = true, markedUnread = true))
        assertEquals(2, notificationUnreadCount(unread, saved))
        assertEquals(3, unread.size)
        assertEquals(1, notificationUnreadCount(emptyMap(), mapOf(alice to PeerPreference(markedUnread = true))))
        assertEquals(0, notificationUnreadCount(emptyMap(), mapOf(alice to PeerPreference(markedUnread = true)), listOf(bob)))
        assertEquals(1, notificationUnreadCount(mapOf("one" to "private:$alice"), mapOf(alice to PeerPreference(markedUnread = true))))
    }
    @Test fun restoredIdentitySignsAndDecryptsButRejectsMismatchedKeys() {
        val first = ChatAuth.ChatSigner.generate()!!
        val other = ChatAuth.ChatSigner.generate()!!
        val restored = ChatAuth.ChatSigner.restore(first.privateKeyBase64(), first.publicKeyBase64())!!
        assertEquals(first.identityId(), restored.identityId())
        assertNull(ChatAuth.ChatSigner.restore(first.privateKeyBase64(), other.publicKeyBase64()))
        assertNull(ChatAuth.ChatSigner.restore("broken", first.publicKeyBase64()))
        val cipher = other.encrypt(first.publicKeyBase64(), "new-session", "/api/chat/send", "hello".toByteArray())
        assertEquals("hello", restored.decrypt(other.publicKeyBase64(), "new-session", "/api/chat/send", cipher).toString(Charsets.UTF_8))
        assertTrue(runCatching { restored.decrypt(other.publicKeyBase64(), "old-session", "/api/chat/send", cipher) }.isFailure)
    }
}
