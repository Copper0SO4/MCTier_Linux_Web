package top.pmh13.mctier.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.pmh13.mctier.data.ChatPeerIdentity
import top.pmh13.mctier.data.ChatSendRequest
import top.pmh13.mctier.data.ChatWireMessage
import top.pmh13.mctier.data.ChatAttachmentMeta
import top.pmh13.mctier.data.ChatMaxAttachmentBytes
import top.pmh13.mctier.data.MctierWireJson
import top.pmh13.mctier.data.MctierJson
import top.pmh13.mctier.data.validChatAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * P2P chat client.
 *
 * The HTTP listener is deliberately inert until the signaling session has
 * supplied a valid chat token and epoch. Peer destinations and message
 * identities are populated from the same authenticated signaling snapshot.
 *
 * Outgoing requests are signed with an ephemeral P-256 key whose public half is
 * published through signaling, so the receiver attributes the request to this
 * member by signature rather than by TCP source address.
 */
class ChatP2PClient(
    private val playerId: String,
    private val scope: CoroutineScope,
    private val bindIp: String,
    private val onMessage: (ChatWireMessage) -> Unit,
    private val attachmentDirectory: File,
    injectedSigner: ChatAuth.ChatSigner? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val server = ChatHttpServer(playerId, bindIp).also {
        it.onMessageReceived = { message -> accept(message) }
        it.encryptionSigner = { signingSigner() }
    }
    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile private var peerIdentities: List<ChatPeerIdentity> = emptyList()
    @Volatile private var localPlayerName: String = ""
    @Volatile private var started = false
    @Volatile private var signer: ChatAuth.ChatSigner? = injectedSigner
    private val signerLock = Any()
    private var lastMessageTime = 0L
    private var hostId: String? = null
    private var announcementRecovery: Job? = null
    private var announcementGeneration = 0L
    private var announcementRevision = 0L

    @Synchronized
    private fun nextMessageId(): String {
        lastMessageTime = maxOf(System.currentTimeMillis(), lastMessageTime + 1)
        return "msg-$playerId-$lastMessageTime-${UUID.randomUUID()}"
    }

    /**
     * Public key that must be handed to signaling before registering.
     *
     * The key pair is created on first use and then reused until [stop], so the
     * value published to signaling and the value used to sign requests cannot
     * drift apart. Returns null only if the platform cannot generate P-256 keys,
     * in which case chat must not start at all.
     */
    fun ensureSigningKey(): String? = synchronized(signerLock) {
        signer?.let { return it.publicKeyBase64() }
        val generated = ChatAuth.ChatSigner.generate()
        if (generated == null) {
            Log.e(TAG, "无法生成聊天签名密钥")
            return null
        }
        signer = generated
        return generated.publicKeyBase64()
    }

    /** The same signer used for chat requests and signaling registration. */
    fun signingSigner(): ChatAuth.ChatSigner? = synchronized(signerLock) { signer }

    fun identityId(): String? = signingSigner()?.identityId()

    /** Install the first session or a newer registration snapshot. */
    @Synchronized
    fun configureSession(token: String, tokenEpoch: Long, playerName: String, hostId: String?): Boolean {
        localPlayerName = playerName
        val publicKey = ensureSigningKey() ?: return false
        val local = ChatPeerIdentity(playerId, playerName, bindIp, publicKey)
        if (!server.configureSession(token, tokenEpoch, local, peerIdentities, hostId)) return false
        this.hostId = hostId
        restartAnnouncementRecovery()
        return true
    }

    /** Apply a signaling-issued token rotation. Lower epochs are ignored. */
    @Synchronized
    fun rotateToken(token: String, tokenEpoch: Long): Boolean {
        if (!server.rotateToken(token, tokenEpoch)) return false
        restartAnnouncementRecovery()
        return true
    }

    @Synchronized
    fun updateHostId(hostId: String?): Boolean {
        if (!server.updateHostId(hostId)) return false
        this.hostId = hostId
        restartAnnouncementRecovery()
        return true
    }

    fun isReady(): Boolean = server.hasSession()

    fun isAuthoritativePeer(expectedPlayerId: String, rawIp: String): Boolean {
        if (!server.hasSession()) return false
        val ip = ChatHttpServer.parseUsableIp(rawIp)?.hostAddress ?: return false
        return peerIdentities.any { it.playerId == expectedPlayerId && it.virtualIp == ip }
    }

    /** Start only after [configureSession] succeeds. */
    @Synchronized
    fun start(): Boolean {
        if (!server.hasSession()) {
            Log.w(TAG, "Chat server start refused before authenticated session")
            return false
        }
        if (started) return true
        return runCatching {
            // NanoHTTPD.start(firstArg, daemon): firstArg is the socket read
            // timeout in milliseconds; the listener port is fixed by the
            // ChatHttpServer constructor (14540).
            server.start(5_000, false)
            started = true
            restartAnnouncementRecovery()
            true
        }.onFailure { Log.w(TAG, "Chat server start failed: ${it.message}") }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        started = false
        restartAnnouncementRecovery()
        hostId = null
        runCatching { server.stop() }
        server.clearSession()
        seen.clear()
        peerIdentities = emptyList()
        localPlayerName = ""
        // 离开大厅即永久作废该会话的签名凭据。
        synchronized(signerLock) { signer = null }
    }

    @Synchronized
    fun resetAuthBaseline() {
        server.resetAuthBaseline()
        restartAnnouncementRecovery()
    }

    /** Update the authoritative peer IP-to-player map from signaling. */
    @Synchronized
    fun setPeers(peers: List<ChatPeerIdentity>): Boolean {
        val normalized = peers
            .asSequence()
            .filter { it.playerId.isNotBlank() && it.playerId != playerId }
            .mapNotNull { peer ->
                ChatHttpServer.parseUsableIp(peer.virtualIp)?.hostAddress?.let { ip -> peer.copy(virtualIp = ip) }
            }
            .toList()
        // 两名成员出现同一把公钥会让归属产生歧义，直接拒绝整份快照。
        val publishedKeys = normalized.mapNotNull { it.chatPublicKey?.takeIf { key -> key.isNotBlank() } }
        if (publishedKeys.distinct().size != publishedKeys.size) {
            Log.w(TAG, "Rejected chat peer snapshot with duplicate signing keys")
            return false
        }
        if (
            normalized.size > MAX_PEERS ||
            normalized.map { it.playerId }.distinct().size != normalized.size ||
            normalized.map { it.virtualIp }.distinct().size != normalized.size
        ) {
            Log.w(TAG, "Rejected duplicate or oversized chat peer snapshot")
            return false
        }
        if (server.hasSession() && !server.updatePeers(normalized)) {
            Log.w(TAG, "Rejected invalid chat peer identity snapshot")
            return false
        }
        val previousHost = peerIdentities.firstOrNull { it.playerId == hostId }
        peerIdentities = normalized
        if (previousHost != normalized.firstOrNull { it.playerId == hostId }) restartAnnouncementRecovery()
        return true
    }

    /** Recover pre-join state from the host, without relying on a timed broadcast. */
    private fun restartAnnouncementRecovery() {
        val generation = ++announcementGeneration
        announcementRecovery?.cancel()
        announcementRecovery = null
        if (!started || !server.hasSession() || hostId == playerId) return
        val host = peerIdentities.firstOrNull { it.playerId == hostId } ?: return
        val token = server.currentToken() ?: return
        val epoch = server.currentTokenEpoch()
        val activeSigner = signingSigner() ?: return
        val revision = announcementRevision
        announcementRecovery = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                val history = fetchHostHistory(host, token, epoch, activeSigner)
                synchronized(this@ChatP2PClient) {
                    if (generation != announcementGeneration) return@launch
                    // Live messages received during the GET always take precedence.
                    if (revision != announcementRevision) return@launch
                    if (history != null) {
                        history.lastOrNull { server.isValidHostAnnouncement(it, host.playerId) }
                            ?.let { accept(it) }
                        return@launch
                    }
                }
                delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
            }
        }
    }

    private fun fetchHostHistory(host: ChatPeerIdentity, token: String, epoch: Long, activeSigner: ChatAuth.ChatSigner): List<ChatWireMessage>? {
        val key = host.chatPublicKey ?: return null
        val path = "/api/chat/messages"
        val signed = activeSigner.sign("GET", path, host.virtualIp, epoch, ChatAuth.unixSeconds(), ByteArray(0), token) ?: return null
        val request = Request.Builder().url("http://${formatHost(host.virtualIp)}:14540$path")
            .header(ChatTokenHeader, token).header(ChatAuth.KeyIdHeader, signed.keyId)
            .header(ChatAuth.SignatureHeader, signed.signature).header(ChatAuth.TimestampHeader, signed.timestamp)
            .header(ChatAuth.NonceHeader, signed.nonce).get().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                // Encryption uses base64; bound both declared and streamed response sizes.
                val maxBytes = top.pmh13.mctier.data.ChatMaxHistoryBytes * 2L
                if (body.contentLength() > maxBytes) return null
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxBytes) return null
                        output.write(buffer, 0, count)
                    }
                }
                val plain = activeSigner.decrypt(key, token, path, output.toByteArray()) ?: return null
                // The history byte budget excludes the surrounding JSON array/separators.
                if (plain.size > top.pmh13.mctier.data.ChatMaxHistoryBytes + 64 * 1024) return null
                MctierJson.decodeFromString(ListSerializer(ChatWireMessage.serializer()), plain.toString(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    fun sendText(playerName: String, content: String, recipientId: String? = null): ChatWireMessage? =
        sendInternal(playerName, content, "text", null, recipientId)

    fun sendImage(playerName: String, imageBytes: List<Int>, recipientId: String? = null, content: String = "[Image]"): ChatWireMessage? =
        sendInternal(playerName, content, "image", imageBytes, recipientId)

    fun sendVoice(playerName: String, bytes: ByteArray, duration: Double, recipientId: String?, mime: String = "audio/wav"): ChatWireMessage? =
        sendInternal(playerName, org.json.JSONObject().put("mime", mime).put("duration", duration).toString(), "voice", bytes.map { it.toInt() and 255 }, recipientId)

    fun sendFile(playerName: String, meta: ChatAttachmentMeta, file: File, recipientId: String?): ChatWireMessage? {
        if (!validChatAttachment(meta) || !server.registerAttachment(meta, file, recipientId)) return null
        val content = MctierJson.encodeToString(ChatAttachmentMeta.serializer(), meta)
        return sendInternal(playerName, content, "file", null, recipientId)
    }

    fun acceptsFileMessages(): Boolean = true

    fun fetchAttachment(ownerPlayerId: String, meta: ChatAttachmentMeta): File? {
        if (!validChatAttachment(meta)) return null
        if (ownerPlayerId == playerId) return server.localAttachment(meta)
        val peer = peerIdentities.firstOrNull { it.playerId == ownerPlayerId } ?: return null
        val key = peer.chatPublicKey ?: return null
        val token = server.currentToken() ?: return null
        val epoch = server.currentTokenEpoch().takeIf { it > 0 } ?: return null
        val activeSigner = synchronized(signerLock) { signer } ?: return null
        val path = "/api/chat/attachment/${meta.id}"
        val signed = activeSigner.sign("GET", path, peer.virtualIp, epoch, ChatAuth.unixSeconds(), ByteArray(0), token) ?: return null
        val request = Request.Builder().url("http://${formatHost(peer.virtualIp)}:14540$path")
            .header(ChatTokenHeader, token).header(ChatAuth.KeyIdHeader, signed.keyId)
            .header(ChatAuth.SignatureHeader, signed.signature).header(ChatAuth.TimestampHeader, signed.timestamp)
            .header(ChatAuth.NonceHeader, signed.nonce).get().build()
        val encrypted = runCatching { client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_ATTACHMENT_RESPONSE_BYTES) return null
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_ATTACHMENT_RESPONSE_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } }.getOrNull() ?: return null
        val plain = runCatching { activeSigner.decrypt(key, token, path, encrypted) }.getOrNull() ?: return null
        if (plain.size.toLong() != meta.size || plain.size > ChatMaxAttachmentBytes) return null
        attachmentDirectory.mkdirs()
        val extension = meta.name.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,16}")) } ?: "bin"
        val target = File(attachmentDirectory, "${ownerPlayerId.replace(Regex("[^A-Za-z0-9]"), "_")}-${meta.id}.$extension")
        return runCatching { target.writeBytes(plain); target }.getOrNull()
    }

    fun sendAnnounce(playerName: String, text: String): ChatWireMessage? =
        sendInternal(playerName, text, "announce", null)

    fun sendVoiceGroup(playerName: String, group: Int): ChatWireMessage? =
        sendInternal(playerName, group.toString(), "voicegroup", null)

    /** 多人协同待办：content 为待办列表 JSON（与桌面端一致，后写覆盖全队同步） */
    fun sendTodo(playerName: String, todosJson: String): ChatWireMessage? =
        sendInternal(playerName, todosJson, "todo", null)

    fun sendRecall(playerName: String, messageId: String, recipientId: String? = null): ChatWireMessage? =
        sendInternal(playerName, messageId, "recall", null, recipientId)

    fun sendAvatar(avatarData: String?): ChatWireMessage? =
        sendInternal(localPlayerName, avatarData.orEmpty(), "avatar", null)

    private fun sendInternal(playerName: String, content: String, type: String, imageData: List<Int>?, recipientId: String? = null): ChatWireMessage? {
        if (!server.hasSession()) {
            Log.w(TAG, "Chat send suppressed before authenticated session")
            return null
        }
        val effectiveName = localPlayerName.ifBlank { playerName }
        val id = nextMessageId()
        val msg = ChatWireMessage(id, playerId, effectiveName, content, type, System.currentTimeMillis() / 1000L, imageData, recipientId)
        if (!server.addLocal(msg)) {
            Log.w(TAG, "Rejected invalid or duplicate local chat message")
            return null
        }
        remember(id)
        val req = ChatSendRequest(id, playerId, effectiveName, content, type, imageData, recipientId)
        // 编码一次并复用同一份字节：签名覆盖的正是这些字节。
        val body = MctierWireJson.encodeToString(ChatSendRequest.serializer(), req)
            .toByteArray(Charsets.UTF_8)
        peerIdentities.filter { recipientId == null || it.playerId == recipientId }.map { it.virtualIp }.distinct().forEach { ip ->
            scope.launch { postWithRetry(ip, body) }
        }
        return msg
    }

    @Synchronized
    private fun accept(msg: ChatWireMessage) {
        if (!server.isKnownPeer(msg)) return
        if (msg.playerId == playerId) return
        if (msg.recipientId != null && msg.recipientId != playerId) return
        if (!remember(msg.id)) return
        if (msg.messageType == "announce") {
            if (!server.isValidHostAnnouncement(msg, hostId)) return
            announcementRevision++
        }
        onMessage(msg)
    }

    /**
     * POST to one peer, signing each attempt separately.
     *
     * A retry must not reuse the previous nonce or timestamp: the receiver's
     * replay guard would reject the identical request as a replay. The audience
     * is the destination peer's virtual IP, which stops that peer from relaying
     * this request to a third member as if freshly authored here.
     */
    private fun postWithRetry(ip: String, plain: ByteArray) {
        repeat(2) { attempt ->
            val token = server.currentToken() ?: return
            val epoch = server.currentTokenEpoch()
            if (epoch <= 0L) return
            val activeSigner = synchronized(signerLock) { signer } ?: return
            val peer = peerIdentities.firstOrNull { it.virtualIp == ip }?.chatPublicKey ?: return
            val body = runCatching { activeSigner.encrypt(peer, token, CHAT_SEND_PATH, plain) }.getOrNull() ?: return
            val signed = activeSigner.sign(
                method = "POST",
                path = CHAT_SEND_PATH,
                audience = ip,
                tokenEpoch = epoch,
                timestamp = ChatAuth.unixSeconds(),
                body = body,
                lobbyToken = token,
            )
            if (signed == null) {
                Log.w(TAG, "聊天请求签名失败，已放弃发送")
                return
            }
            val result = runCatching {
                val req = Request.Builder()
                    .url("http://${formatHost(ip)}:14540$CHAT_SEND_PATH")
                    .header(ChatTokenHeader, token)
                    .header(ChatAuth.KeyIdHeader, signed.keyId)
                    .header(ChatAuth.SignatureHeader, signed.signature)
                    .header(ChatAuth.TimestampHeader, signed.timestamp)
                    .header(ChatAuth.NonceHeader, signed.nonce)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { response ->
                    response.code to response.body?.string()?.take(240)
                }
            }
            val ok = result.getOrNull()?.first?.let { it in 200..299 } == true
            if (ok) return
            val failureBody = result.getOrNull()?.second?.replace(Regex("\\s+"), " ")?.take(160)
            Log.w(TAG, "聊天发送到 $ip 失败: HTTP ${result.getOrNull()?.first ?: "network"} body=${failureBody ?: ""} (attempt=${attempt + 1})")
            if (attempt == 0) Thread.sleep(400)
        }
    }

    private fun remember(id: String): Boolean = synchronized(seen) {
        if (!seen.add(id)) return false
        while (seen.size > MAX_SEEN_IDS) {
            val first = seen.firstOrNull() ?: break
            seen.remove(first)
        }
        true
    }

    private fun formatHost(ip: String): String = if (ip.contains(':')) "[$ip]" else ip

    private companion object {
        private const val TAG = "ChatP2PClient"
        private const val MAX_SEEN_IDS = 1000
        private const val MAX_PEERS = 64
        private const val ChatTokenHeader = "x-mctier-chat-token"
        private const val CHAT_SEND_PATH = "/api/chat/send"
        private const val MAX_ATTACHMENT_RESPONSE_BYTES = 90 * 1024 * 1024
    }
}
