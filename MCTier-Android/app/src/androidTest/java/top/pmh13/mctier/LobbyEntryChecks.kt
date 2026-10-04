package top.pmh13.mctier

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import top.pmh13.mctier.network.*

/** Test-only loopback transport. Production still requires WSS. adb reverse tcp:18445 tcp:18445. */
internal class LobbyEntryChecks(private val test: Instrumentation, private val serverUrl: String? = null) {
    fun run() {
        val result = Bundle()
        var code = 0
        val clients = mutableListOf<SignalingClient>()
        val http = OkHttpClient()
        val factory = if (serverUrl != null) http else WebSocket.Factory { request, listener ->
            http.newWebSocket(request.newBuilder().url("http://127.0.0.1:18445").build(), listener)
        }
        var activity: MainActivity? = null
        try {
            runBlocking {
                val room = "Entry${System.currentTimeMillis()}"
                suspend fun enter(mode: String, password: String, host: Int): Result<Unit> {
                    val signer = checkNotNull(ChatAuth.ChatSigner.generate())
                    val client = SignalingClient(factory).also { clients.add(it) }
                    val args = ConnectArgs(serverUrl ?: "wss://fixture.invalid", signer.identityId(), "Test$host", room,
                        password, "10.126.126.$host", signer, entryMode = mode)
                    return runCatching { client.connectAndAwaitRegistration(args) }
                }
                check(enter("join", "wrongpass", 2).exceptionOrNull()?.message?.contains("大厅不存在") == true)
                check(enter("create", "correctpass", 1).isSuccess)
                check(enter("create", "wrongpass", 2).exceptionOrNull()?.message?.contains("名称已被占用") == true)
                check(enter("create", "correctpass", 2).exceptionOrNull()?.message?.contains("名称已被占用") == true)
                check(enter("join", "wrongpass", 2).exceptionOrNull()?.message == "密码错误")
                check(enter("join", "correctpass", 2).isSuccess)
                clients.forEach { it.close() }
            }
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val errors = listOf("大厅名称已被占用，请更换大厅名称后重试", "大厅不存在或已关闭，请检查大厅名称或联系房主", "密码错误")
            test.runOnMainSync {
                activity!!.setContent { MaterialTheme { Column(Modifier.width(260.dp)) {
                    errors.forEach { top.pmh13.mctier.ui.LobbyEntryError(it) }
                } } }
            }
            fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
                if (node == null) return false
                if (node.text?.toString() == text && node.isVisibleToUser) return true
                return (0 until node.childCount).any { contains(node.getChild(it), text) }
            }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline && !errors.all { contains(test.uiAutomation.rootInActiveWindow, it) }) SystemClock.sleep(100)
            check(errors.all { contains(test.uiAutomation.rootInActiveWindow, it) }) { "Full rejection text must be visible on a narrow screen" }
            result.putString("stream", "PASS: live server missing join, create, duplicate create with both passwords, wrong-password join, valid join; all full error messages visible at 260dp.\n")
        } catch (error: Throwable) {
            code = 1; result.putString("stream", "FAIL: ${error.stackTraceToString()}")
        } finally {
            clients.forEach { it.close() }
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            test.runOnMainSync { activity?.setContent {}; activity?.finish() }
            test.finish(code, result)
        }
    }
}
