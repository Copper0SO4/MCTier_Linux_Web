package top.pmh13.mctier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.pmh13.mctier.data.ChatPeerIdentity
import kotlinx.coroutines.cancel
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import top.pmh13.mctier.data.ChatWireMessage

class SecurityHardeningTest {
    private class AnnouncementFixture : java.io.Closeable {
        val hostKey = ChatAuth.ChatSigner.generate()!!
        val memberKey = ChatAuth.ChatSigner.generate()!!
        val host = ChatPeerIdentity(hostKey.identityId(), "host", "10.126.126.1", hostKey.publicKeyBase64())
        val token = "a".repeat(64)
        val received = java.util.concurrent.LinkedBlockingQueue<ChatWireMessage>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val client = ChatP2PClient(memberKey.identityId(), scope, "10.126.126.2", { received.add(it) }, java.io.File("unused"), memberKey)
        var respond: (okhttp3.Request) -> List<ChatWireMessage> = { emptyList() }
        fun message(content: String, suffix: String = "1") = ChatWireMessage(
            "msg-${host.playerId}-$suffix", host.playerId, host.playerName, content, "announce", 100L,
        )
        fun start() {
            val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("/api/chat/messages", request.url.encodedPath)
                assertEquals(token, request.header("x-mctier-chat-token"))
                assertTrue(!request.header(ChatAuth.SignatureHeader).isNullOrBlank())
                val history = respond(request)
                val plain = top.pmh13.mctier.data.MctierJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatWireMessage.serializer()), history)
                val encrypted = hostKey.encrypt(memberKey.publicKeyBase64(), token, "/api/chat/messages", plain.toByteArray())
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(encrypted.toResponseBody("application/json".toMediaType())).build()
            }.build()
            ChatP2PClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(client, transport)
            assertTrue(client.setPeers(listOf(host)))
            // No VPN interface is needed for this deterministic encrypted transport fixture.
            ChatP2PClient::class.java.getDeclaredField("started").apply { isAccessible = true }.set(client, true)
            assertTrue(client.configureSession(token, 1L, "member", host.playerId))
        }
        fun live(message: ChatWireMessage) {
            ChatP2PClient::class.java.getDeclaredMethod("accept", ChatWireMessage::class.java).apply { isAccessible = true }.invoke(client, message)
        }
        override fun close() { client.stop(); scope.cancel() }
    }

    @Test fun joiningRecoversLatestHostAnnouncementAfterTransientFailure() {
        AnnouncementFixture().use { f ->
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            f.respond = {
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("host not ready")
                listOf(f.message("old"), f.message("入厅前发布的公告", "2"), f.message("forged", "3").copy(playerId = f.memberKey.identityId()))
            }
            f.start()
            assertEquals("入厅前发布的公告", f.received.poll(5, java.util.concurrent.TimeUnit.SECONDS)?.content)
            assertEquals(2, attempts.get())
        }
    }

    @Test fun recoveredEmptyAnnouncementClearsPreviousContent() {
        AnnouncementFixture().use { f ->
            f.respond = { listOf(f.message("old"), f.message("", "2")) }
            f.start()
            assertEquals("", f.received.poll(3, java.util.concurrent.TimeUnit.SECONDS)?.content)
        }
    }

    @Test fun historyCannotOverwriteLiveAnnouncementOrSurviveSessionExitOrHostChange() {
        for (action in listOf("live", "stop", "host")) AnnouncementFixture().use { f ->
            val requested = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            f.respond = {
                requested.countDown()
                check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                listOf(f.message("stale"))
            }
            f.start()
            try {
                assertTrue(requested.await(3, java.util.concurrent.TimeUnit.SECONDS))
                if (action == "stop") f.client.stop()
                else if (action == "host") assertTrue(f.client.updateHostId(f.memberKey.identityId()))
                else {
                    f.live(f.message("fresh", "2"))
                    assertEquals("fresh", f.received.poll(1, java.util.concurrent.TimeUnit.SECONDS)?.content)
                }
            } finally { release.countDown() }
            assertEquals(null, f.received.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }

    @Test fun recoveredAnnouncementRequiresCurrentHostAndPublicValidPayload() {
        AnnouncementFixture().use { f ->
            val server = ChatHttpServer(f.memberKey.identityId(), "10.126.126.2")
            val member = ChatPeerIdentity(f.memberKey.identityId(), "member", "10.126.126.2", f.memberKey.publicKeyBase64())
            assertTrue(server.configureSession(f.token, 1L, member, listOf(f.host), f.host.playerId))
            assertTrue(server.isValidHostAnnouncement(f.message("notice"), f.host.playerId))
            assertFalse(server.isValidHostAnnouncement(f.message("notice").copy(recipientId = member.playerId), f.host.playerId))
            assertFalse(server.isValidHostAnnouncement(f.message("notice").copy(playerName = "impostor"), f.host.playerId))
            assertFalse(server.isValidHostAnnouncement(f.message("x".repeat(16385)), f.host.playerId))
            assertTrue(server.updateHostId(member.playerId))
            assertFalse(server.isValidHostAnnouncement(f.message("notice"), f.host.playerId))
        }
    }

    @Test
    fun signalingBusinessMessagesRequireAcceptedRegistration() {
        val signaling = SignalingClient()
        val sent = mutableListOf<String>()
        val socket = object : okhttp3.WebSocket {
            override fun request() = okhttp3.Request.Builder().url("https://localhost").build()
            override fun queueSize() = 0L
            override fun send(text: String): Boolean { sent.add(text); return true }
            override fun send(bytes: okio.ByteString) = false
            override fun close(code: Int, reason: String?) = true
            override fun cancel() {}
        }
        fun setField(name: String, value: Any?) {
            SignalingClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(signaling, value)
        }
        setField("webSocket", socket)
        val request = top.pmh13.mctier.data.SignalingEnvelope(type = "players-list-request")
        assertFalse(signaling.send(request))
        assertTrue(signaling.send(top.pmh13.mctier.data.SignalingEnvelope(type = "register-v3")))
        setField("serverSessionGeneration", 1234567890123456L)
        assertFalse(signaling.send(request))
        @Suppress("UNCHECKED_CAST")
        val connected = SignalingClient::class.java.getDeclaredField("_connected").apply { isAccessible = true }
            .get(signaling) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        connected.value = true
        assertTrue(signaling.send(request))
        assertTrue(sent.last().contains("1234567890123456"))
        connected.value = false
        assertFalse(signaling.send(request))
        assertEquals(2, sent.size)
    }

    @Test
    fun chatAuthBaselineCanResetAfterSignalingServerRestart() {
        val localSigner = ChatAuth.ChatSigner.generate() ?: error("P-256 unavailable")
        val server = ChatHttpServer(localSigner.identityId(), "10.126.126.7")
        val local = ChatPeerIdentity(
            localSigner.identityId(),
            "local",
            "10.126.126.7",
            localSigner.publicKeyBase64(),
        )

        assertTrue(server.configureSession("a".repeat(64), 7, local, emptyList(), local.playerId))
        assertFalse(server.configureSession("b".repeat(64), 1, local, emptyList(), local.playerId))
        server.resetAuthBaseline()
        assertTrue(server.configureSession("b".repeat(64), 1, local, emptyList(), local.playerId))
    }

    @Test
    fun signalingChallengeSignatureBindsContextAndDerivesIdentity() {
        val signer = ChatAuth.ChatSigner.generate() ?: error("P-256 unavailable")
        val challenge = "ab".repeat(32)
        val lobbyName = "lobby-a"
        val virtualIp = "10.126.126.7"
        val signature = signer.signSignalingRegistration(challenge, lobbyName, virtualIp)
            ?: error("signing failed")
        val der = ChatAuth.parsePublicKey(signer.publicKeyBase64()) ?: error("key parse failed")

        assertTrue(
            ChatAuth.verifySignature(
                der,
                signature,
                ChatAuth.canonicalSignalingRegistration(challenge, lobbyName, virtualIp),
            ),
        )
        assertFalse(
            ChatAuth.verifySignature(
                der,
                signature,
                ChatAuth.canonicalSignalingRegistration("cd".repeat(32), lobbyName, virtualIp),
            ),
        )
        assertFalse(
            ChatAuth.verifySignature(
                der,
                signature,
                ChatAuth.canonicalSignalingRegistration(challenge, "lobby-b", virtualIp),
            ),
        )
        assertEquals(signer.identityId(), ChatAuth.identityIdForPublicKey(der))
        assertEquals(
            "${signer.identityId().substring(0, 32)}.mct.net",
            ChatAuth.virtualDomainForIdentityId(signer.identityId()),
        )
    }

    @Test
    fun boundedIceCacheEnforcesPeerEntryByteAndTtlLimits() {
        var now = 0L
        val cache = BoundedIceCache<String, String>(
            maxEntries = 3,
            maxBytes = 10,
            maxEntriesPerPeer = 2,
            ttlMillis = 100,
            peerOf = { it.substringBefore('|') },
            bytesOf = { it.length },
            clockMillis = { now },
        )

        assertTrue(cache.add("peer-a|route-1", "1234"))
        assertTrue(cache.add("peer-a|route-2", "5678"))
        assertTrue(cache.add("peer-a|route-1", "zzzz"))
        assertEquals(2, cache.size())
        assertTrue(cache.add("peer-b|route-1", "12"))
        assertTrue(cache.byteSize() <= 10)

        now = 101
        assertEquals(0, cache.size())
        assertEquals(0, cache.byteSize())
    }

    @Test
    fun passwordBackoffIsPerKeyAndResetsOnSuccess() {
        var now = 0L
        val limiter = ExponentialBackoffLimiter(
            maxEntries = 4,
            baseDelayMillis = 100,
            maxDelayMillis = 1_000,
            ttlMillis = 10_000,
            clockMillis = { now },
        )

        assertTrue(limiter.beforeAttempt("share-a|viewer-a").allowed)
        assertEquals(100, limiter.recordFailure("share-a|viewer-a"))
        assertFalse(limiter.beforeAttempt("share-a|viewer-a").allowed)
        assertTrue(limiter.beforeAttempt("share-a|viewer-b").allowed)
        now = 100
        assertTrue(limiter.beforeAttempt("share-a|viewer-a").allowed)
        assertEquals(200, limiter.recordFailure("share-a|viewer-a"))
        limiter.recordSuccess("share-a|viewer-a")
        assertTrue(limiter.beforeAttempt("share-a|viewer-a").allowed)
    }
}
