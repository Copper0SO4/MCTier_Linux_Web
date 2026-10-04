package top.pmh13.mctier.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class SignalingRegistrationTest {
    private class Socket(private val req: Request, val listener: WebSocketListener) : WebSocket {
        var closed = false
        val sent = mutableListOf<String>()
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { if (closed) return false; sent.add(text); return true }
        override fun send(bytes: ByteString) = !closed
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }
    private class Factory : WebSocket.Factory {
        val sockets = mutableListOf<Socket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request, listener).also { sockets.add(it) }
    }
    private fun args(): ConnectArgs {
        val signer = checkNotNull(ChatAuth.ChatSigner.generate())
        return ConnectArgs("wss://test.example", signer.identityId(), "Alice", "room", "", "10.126.126.20", signer)
    }

    @Test fun rejectionRetainsItsCauseAndClosesTheRejectedSocket() = runBlocking {
        for (reason in listOf("密码错误", LobbyAddress.Conflict, "大厅名称已被占用，请更换大厅名称后重试", "大厅不存在或已关闭，请检查大厅名称或联系房主")) {
            val factory = Factory()
            val client = SignalingClient(factory)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.connectAndAwaitRegistration(args()) } }
            val socket = factory.sockets.single()
            assertFalse(pending.isCompleted)
            socket.listener.onMessage(socket, """{"type":"register-error","message":"$reason"}""")
            // A following close must not hide the rejection or schedule a retry.
            socket.listener.onClosed(socket, 1008, "rejected")
            val error = pending.await().exceptionOrNull()
            assertTrue(error is RegistrationRejected)
            assertEquals(reason, error?.message)
            assertTrue(socket.closed)
            assertFalse(client.connected.value)
            assertEquals(1, factory.sockets.size)
            client.close()
        }
    }

    @Test fun registrationSendsExplicitModeAndFailsClosedOnOldServers() = runBlocking {
        for (mode in listOf("create", "join", "auto")) {
            val factory = Factory()
            val client = SignalingClient(factory)
            val args = args().copy(entryMode = mode)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.connectAndAwaitRegistration(args) } }
            val socket = factory.sockets.single()
            socket.listener.onMessage(socket, """{"type":"server-challenge","protocolVersion":3,"lobbyEntryModes":true,"challenge":"${"a".repeat(64)}"}""")
            val wire = org.json.JSONObject(socket.sent.single())
            assertEquals(mode, wire.getString("entryMode"))
            assertFalse(pending.isCompleted)
            socket.listener.onMessage(socket, """{"type":"register-success","clientId":"${args.identityId}","sessionGeneration":123}""")
            assertTrue(pending.await().isSuccess)
            client.close()
        }
        val factory = Factory()
        val client = SignalingClient(factory)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.connectAndAwaitRegistration(args()) } }
        val socket = factory.sockets.single()
        socket.listener.onMessage(socket, """{"type":"server-challenge","protocolVersion":3,"challenge":"${"a".repeat(64)}"}""")
        assertTrue(pending.await().exceptionOrNull()?.message.orEmpty().contains("管理员升级"))
        assertTrue(socket.sent.isEmpty())
        assertTrue(socket.closed)
        client.close()
    }

    @Test fun registrationMustCompleteBeforeConnectReturnsAndOldSocketsCannotCompleteANewAttempt() = runBlocking {
        val factory = Factory()
        val client = SignalingClient(factory)
        val args = args()
        val old = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.connectAndAwaitRegistration(args) } }
        val firstSocket = factory.sockets.single()
        val current = async(start = CoroutineStart.UNDISPATCHED) { client.connectAndAwaitRegistration(args) }
        val success = """{"type":"register-success","clientId":"${args.identityId}","sessionGeneration":123}"""
        firstSocket.listener.onMessage(firstSocket, success)
        assertFalse(current.isCompleted)
        val socket = factory.sockets.last()
        socket.listener.onMessage(socket, success)
        current.await()
        assertTrue(old.await().isFailure)
        assertTrue(client.connected.value)
        client.close()
    }
}
