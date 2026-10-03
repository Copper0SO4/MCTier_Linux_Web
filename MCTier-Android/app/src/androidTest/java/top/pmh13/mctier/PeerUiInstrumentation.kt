package top.pmh13.mctier

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import top.pmh13.mctier.network.AndroidRtcController
import top.pmh13.mctier.network.ChatP2PClient
import top.pmh13.mctier.data.*
import top.pmh13.mctier.network.ChatAuth
import java.io.ByteArrayOutputStream
import java.io.File

/** Test APK only. Synthetic peers exercise the actual Compose UI, not network delivery. */
class PeerUiInstrumentation : Instrumentation() {
    private var checkMode: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); checkMode = arguments?.getString("check"); start() }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label || node.viewIdResourceName == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { hit -> return hit } }
            return null
        }
        return uiAutomation.windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { it.root?.let(::visit) }
            ?: uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitUntil(label: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(100) }
        error("Timed out: $label")
    }
    private fun act(label: String, long: Boolean = false) {
        waitUntil(label) { find(label) != null }
        var node = find(label)!!
        while (!(if (long) node.isLongClickable else node.isClickable)) node = node.parent ?: error("No action for $label")
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val now = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            check(uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        touch(android.view.MotionEvent.ACTION_DOWN)
        SystemClock.sleep(if (long) 700 else 60)
        touch(android.view.MotionEvent.ACTION_UP)
        SystemClock.sleep(400)
    }
    private fun screenshot(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val folder = File(targetContext.getExternalFilesDir(null), "peer-ui-tests").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun setDraft(text: String) {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.refresh()
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { found -> return found } }
            return null
        }
        val input = uiAutomation.windows.firstNotNullOfOrNull { it.root?.let(::editable) } ?: error("No chat editor")
        check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
        SystemClock.sleep(250)
    }
    private fun testVoiceWidths(state: MutableStateFlow<MctierUiState>) {
        val original = state.value.chatMessages
        val widths = mutableListOf<Int>()
        try {
            for (seconds in listOf(1, 5, 10, 20)) {
                val samples = ByteArray(32000 * seconds)
                val wav = java.nio.ByteBuffer.allocate(44 + samples.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                wav.put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVEfmt ".toByteArray())
                wav.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                wav.put("data".toByteArray()).putInt(samples.size).put(samples)
                val data = "data:audio/wav;base64," + android.util.Base64.encodeToString(wav.array(), android.util.Base64.NO_WRAP)
                runOnMainSync { state.value = state.value.copy(chatMessages = listOf(ChatMessage("width-$seconds", state.value.playerId,
                    "Width", "{\"duration\":$seconds}", System.currentTimeMillis(), mine = true, type = "voice", imageBase64 = data))) }
                waitUntil("duration $seconds") { find("${seconds}s") != null }
                SystemClock.sleep(800)
                val bubble = android.graphics.Rect().also { find("voice-bubble")!!.getBoundsInScreen(it) }
                widths += bubble.width()
                val background = android.graphics.Rect().also { find("voice-play-background")!!.getBoundsInScreen(it) }
                check(background.width() == (22 * targetContext.resources.displayMetrics.density).toInt()) { "Play background expanded: $background" }
                screenshot("voice-width-$seconds")
            }
            check(widths[0] < widths[1] && widths[1] < widths[2] && widths[2] == widths[3]) { "Actual voice widths do not scale: $widths" }
            android.util.Log.i("MctierUiTest", "Voice widths 1/5/10/20 seconds: $widths px; play background: 22 dp")
        } finally { runOnMainSync { state.value = state.value.copy(chatMessages = original) } }
    }
    private fun testVoice(repository: MctierRepository, state: MutableStateFlow<MctierUiState>, identity: ChatAuth.ChatSigner) {
        val clientField = MctierRepository::class.java.getDeclaredField("chatClient").apply { isAccessible = true }
        val rtc = MctierRepository::class.java.getDeclaredField("rtcController").apply { isAccessible = true }.get(repository) as AndroidRtcController
        val oldClient = clientField.get(repository)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = ChatP2PClient(state.value.playerId, scope, "10.126.126.1", {}, File(targetContext.cacheDir, "voice-ui-test"), identity)
        clientField.set(repository, client)
        uiAutomation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.RECORD_AUDIO)
        fun bounds(label: String) = android.graphics.Rect().also { find(label)!!.getBoundsInScreen(it) }
        fun toolbar() = listOf("表情", "发送文件", "发送").map(::bounds)
        try {
            val countBefore = state.value.chatMessages.size
            runOnMainSync { state.value = state.value.copy(chatReady = false, chatConnectionError = "HTTP 525: SSL handshake failed with origin server") }
            setDraft("Unsent draft")
            act("发送")
            check(find("Unsent draft") != null) { "Failed send erased draft" }
            check(state.value.chatMessages.size == countBefore) { "Unauthenticated send appeared successful" }
            screenshot("chat-unavailable-draft")
            check(client.configureSession("d".repeat(64), 1, state.value.settings.playerName, state.value.playerId))
            runOnMainSync { state.value = state.value.copy(chatReady = true, chatConnectionError = null) }
            act("发送")
            waitUntil("text appears after authentication") { state.value.chatMessages.any { it.content == "Unsent draft" && it.mine } }
            waitUntil("draft cleared on success") { find("长按发送语音") != null }
            SystemClock.sleep(3500)
            testVoiceWidths(state)
            runOnMainSync { rtc.initialize(state.value.playerId) {}; rtc.setMicEnabled(true); rtc.ensurePeer("voice-test-peer") }
            val track = AndroidRtcController::class.java.getDeclaredField("localAudioTrack").apply { isAccessible = true }.get(rtc) as org.webrtc.AudioTrack
            check(track.enabled())
            waitUntil("voice placeholder") { find("长按发送语音") != null }
            SystemClock.sleep(400)
            val before = toolbar()
            var downTime = 0L
            var input = bounds("长按发送语音")
            fun touch(action: Int, y: Float = input.exactCenterY()) {
                if (action == android.view.MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
                val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, input.exactCenterX(), y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
            fun hold() {
                input = bounds("长按发送语音")
                touch(android.view.MotionEvent.ACTION_DOWN)
                waitUntil("recording started") { find("上滑取消") != null }
                check(!track.enabled()) { "Lobby microphone is live during recording" }
                check(toolbar() == before) { "Composer icons moved during recording: $before -> ${toolbar()}" }
                check(uiAutomation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) { "Holding opened the keyboard" }
                check(find("粘贴") == null && find("全选") == null) { "Native context menu opened" }
            }
            val count = state.value.chatMessages.count { it.type == "voice" }
            hold(); SystemClock.sleep(1300); screenshot("voice-hold-stable")
            touch(android.view.MotionEvent.ACTION_UP)
            waitUntil("voice message appended") { state.value.chatMessages.count { it.type == "voice" } == count + 1 }
            val voice = state.value.chatMessages.last { it.type == "voice" }
            check(voice.mine && voice.imageBase64?.startsWith("data:audio/wav;base64,") == true)
            check(android.util.Base64.decode(voice.imageBase64!!.substringAfter(','), android.util.Base64.DEFAULT).size > 16044)
            check(track.enabled()) { "Lobby microphone was not restored" }
            SystemClock.sleep(400); check(toolbar() == before); screenshot("voice-sent")
            hold(); SystemClock.sleep(650)
            runOnMainSync { rtc.setMicEnabled(false) }
            for (step in 1..12) { touch(android.view.MotionEvent.ACTION_MOVE, input.exactCenterY() - step * 20); SystemClock.sleep(20) }
            waitUntil("cancel affordance") { find("松开取消") != null }
            touch(android.view.MotionEvent.ACTION_UP, input.exactCenterY() - 240)
            SystemClock.sleep(600)
            check(state.value.chatMessages.count { it.type == "voice" } == count + 1)
            check(!track.enabled()) { "Restoration overwrote user's mic-off choice" }
            touch(android.view.MotionEvent.ACTION_DOWN); SystemClock.sleep(60); touch(android.view.MotionEvent.ACTION_UP)
            waitUntil("tap requests keyboard") {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("dumpsys input_method"))
                    .bufferedReader().use { it.readText() }.contains("mShowRequested=true")
            }
            screenshot("voice-tap-focus")
            if (uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } finally {
            clientField.set(repository, oldClient)
            client.stop(); scope.cancel()
            runOnMainSync { rtc.cleanup() }
        }
    }
    override fun onStart() {
        if (checkMode == "quark-work") { QuarkWorkChecks(this).run(); return }
        if (checkMode?.startsWith("quark-upgrade-") == true) { QuarkUpgradeChecks(this).run(checkMode!!.removePrefix("quark-upgrade-")); return }
        if (checkMode == "ux") { QuarkMediaChecks(this).run(ux = true); return }
        if (checkMode == "startup-prompts") { StartupPromptChecks(this).run(); return }
        if (checkMode == "recording") { RecordingChecks(this).run(); return }
        if (checkMode == "remote-live") {
            RemoteDesktopCheck(this).run()
            return
        }
        if (checkMode == "quark-media" || checkMode == "remote-frame" || checkMode == "quark-buttons") {
            QuarkMediaChecks(this).run(checkMode == "remote-frame", checkMode == "quark-buttons")
            return
        }
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val result = Bundle()
        var resultCode = 0
        val repository = MctierRepository.get(targetContext)
        val field = MctierRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val state = field.get(repository) as MutableStateFlow<MctierUiState>
        val original = state.value
        val prefs = targetContext.getSharedPreferences("mctier", 0)
        val oldPreferences = prefs.getString("private_peer_preferences_v1", null)
        var activity: android.app.Activity? = null
        try {
            check(original.state != AppConnectionState.InLobby) { "Leave the real lobby before running synthetic UI tests" }
            val identityMethod = MctierRepository::class.java.getDeclaredMethod("localChatIdentity").apply { isAccessible = true }
            val identity = identityMethod.invoke(repository) as ChatAuth.ChatSigner
            val restored = identityMethod.invoke(repository) as ChatAuth.ChatSigner
            check(identity.identityId() == restored.identityId()) { "Keystore identity changed" }
            val stored = prefs.getString("chat_identity_p256_v1", "")!!
            check(!stored.contains(identity.privateKeyBase64())) { "Private key is not protected" }
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(Color.rgb(48, 163, 101)); drawText("M", 20f, 72f, Paint().apply { color = Color.WHITE; textSize = 64f }) }
            val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val image = "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            val alice = "a".repeat(64); val bob = "b".repeat(64); val me = identity.identityId()
            val peers = listOf(Player(me, "UiSelf"), Player(alice, "UiAlice", avatarData = image), Player(bob, "UiBob"))
            val messages = listOf(ChatMessage("avatar-public", alice, "UiAlice", "Avatar lobby fixture", System.currentTimeMillis(), mine = false),
                ChatMessage("avatar-private", alice, "UiAlice", "Avatar private fixture", System.currentTimeMillis(), mine = false, recipientId = me))
            runOnMainSync { state.value = original.copy(state = AppConnectionState.InLobby, playerId = me,
                lobby = Lobby("ui-fixture", "UI fixture", "", 0, "10.0.0.1"), players = peers, chatMessages = messages,
                peerPreferences = emptyMap(), unreadChatMessages = emptyMap(), showOnboarding = false, error = null) }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil("lobby avatar") { find("UiAlice 的头像") != null }
            act("UiAlice 的头像"); waitUntil("avatar viewer") { find("关闭") != null }; screenshot("avatar-player-list"); act("关闭")
            act("聊天室")
            waitUntil("lobby chat ready") { find("Avatar lobby fixture") != null }
            act("UiAlice 的头像"); waitUntil("chat avatar viewer") { find("关闭") != null }; screenshot("avatar-lobby-chat"); act("关闭")
            testVoice(repository, state, identity)
            act("私聊")
            act("UiAlice 的头像"); waitUntil("private list viewer") { find("关闭") != null }; act("关闭")
            check(find("选择要私聊的玩家") != null) { "Avatar tap opened the conversation" }
            act("UiAlice", true); act("置顶")
            check(state.value.peerPreferences[alice]?.pinned == true)
            act("UiAlice", true); act("设置免打扰")
            act("UiAlice", true); act("标记未读")
            check(state.value.peerPreferences[alice] == PeerPreference(true, true, true))
            screenshot("private-preferences-dark")
            runOnMainSync { state.value = state.value.copy(players = peers.filterNot { it.id == alice }) }
            SystemClock.sleep(300)
            runOnMainSync { state.value = state.value.copy(players = peers) }
            waitUntil("rejoin") { find("UiAlice") != null }
            check(state.value.peerPreferences[alice] == PeerPreference(true, true, true))
            runOnMainSync { top.pmh13.mctier.ui.applyAppTheme("light", original.settings.themePrimary) }
            SystemClock.sleep(500); act("UiAlice", true); screenshot("private-menu-light"); sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            act("UiAlice")
            waitUntil("manual unread cleared") { state.value.peerPreferences[alice]?.markedUnread == false }
            act("UiAlice 的头像"); waitUntil("private chat avatar") { find("关闭") != null }; screenshot("avatar-private-chat"); act("关闭")
            setDraft("Private unsent draft"); act("发送")
            check(find("Private unsent draft") != null) { "Failed private send erased draft" }
            check(state.value.chatMessages.none { it.content == "Private unsent draft" })
            result.putString("stream", "PASS: unauthenticated lobby/private send preserves drafts; authenticated local text send renders; actual 1/5/10/20-second WAV bubble widths and 22dp play background; real AudioRecord hold/release WAV message; stable toolbar; no IME/context menu on hold; tap requests IME; slide cancels; lobby mic isolation/restoration; Keystore; avatars; pin/DND/unread; light/dark UI. Synthetic authenticated session, not production network delivery.\n")
        } catch (error: Throwable) {
            runCatching { screenshot("failure") }
            fun labels(node: AccessibilityNodeInfo): String = "${node.text}|${node.contentDescription}|click=${node.isClickable}\n" + (0 until node.childCount).joinToString("") { node.getChild(it)?.let(::labels).orEmpty() }
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n${uiAutomation.rootInActiveWindow?.let(::labels)}")
            resultCode = 1
        } finally {
            runOnMainSync {
                state.value = original
                top.pmh13.mctier.ui.applyAppTheme(original.settings.themeMode, original.settings.themePrimary)
                activity?.finish()
            }
            prefs.edit().apply { if (oldPreferences == null) remove("private_peer_preferences_v1") else putString("private_peer_preferences_v1", oldPreferences) }.commit()
        }
        finish(resultCode, result)
    }
}
