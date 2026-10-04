package top.pmh13.mctier

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.pmh13.mctier.data.PublicLobbyWire
import top.pmh13.mctier.network.AndroidRtcController
import top.pmh13.mctier.ui.PublicLobbyCard

/** Actual RTC lifecycle and narrow Compose card; no real lobby or external call required. */
internal class LobbyCompatibilityChecks(private val test: Instrumentation) {
    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return test.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    fun run() {
        val result = Bundle()
        var code = 0
        val context = test.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousMode = audio.mode
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val consent = context.getSharedPreferences("mctier_compliance", 0)
        val hadConsent = consent.contains("agreed_v1")
        val previousConsent = consent.getBoolean("agreed_v1", false)
        val previousLanguage = if (top.pmh13.mctier.ui.L("zh", "en") == "en") "en" else "zh"
        var host: MainActivity? = null
        var simulatedCall: android.media.AudioTrack? = null
        val rtc = AndroidRtcController(context)
        try {
            check(previousMode == AudioManager.MODE_NORMAL) { "End real calls before this audio fixture" }
            // Temporarily simulate an existing communication session; the old code
            // changed this to NORMAL on initialization, mic changes and cleanup.
            // Android releases idle communication owners after a few seconds. Keep
            // a silent call track playing so that this fixture models an active call.
            simulatedCall = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(8000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(16000)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).build().also {
                    check(it.write(ByteArray(16000), 0, 16000) == 16000)
                    check(it.setLoopPoints(0, 8000, -1) == android.media.AudioTrack.SUCCESS)
                    it.play()
                }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            SystemClock.sleep(400)
            check(audio.mode == AudioManager.MODE_IN_COMMUNICATION) { "Device cannot establish the simulated call mode" }
            fun unchanged(stage: String) {
                SystemClock.sleep(900) // Also catch the former delayed routing reset.
                check(audio.mode == AudioManager.MODE_IN_COMMUNICATION) { "$stage reset the existing call mode" }
                check(audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == previousVolume) { "$stage changed call volume" }
            }
            test.runOnMainSync { rtc.initialize("audio-compatibility") {} }
            unchanged("initialize")
            test.runOnMainSync { rtc.setMicEnabled(true); rtc.setMicEnabled(false) }
            unchanged("mic toggle")
            test.runOnMainSync { rtc.cleanup() }
            unchanged("cleanup")
            simulatedCall?.stop()
            simulatedCall?.release()
            simulatedCall = null
            audio.mode = previousMode

            consent.edit().putBoolean("agreed_v1", false).commit()
            host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val description = "生存，游戏 mod 在共享文件夹，版本 1.20.1，forge 最新版，端口号 25565"
            var selected = false
            test.runOnMainSync {
                top.pmh13.mctier.ui.applyAppLanguage("zh")
                host.setContent {
                    MaterialTheme {
                        Column(Modifier.width(280.dp)) {
                            PublicLobbyCard(PublicLobbyWire("生存世界", hostName = "hiyanyan", description = description)) { selected = true }
                            PublicLobbyCard(PublicLobbyWire("无描述", description = "  ")) {}
                        }
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (find(description) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val desc = find(description) ?: error("Public lobby description missing")
            check(desc.isVisibleToUser)
            val bounds = Rect().also(desc::getBoundsInScreen)
            check(bounds.height() > 20 * context.resources.displayMetrics.density) { "Description did not wrap on a narrow card" }
            var card = desc
            while (!card.isClickable) card = card.parent ?: error("Lobby selection missing")
            val cardBounds = Rect().also(card::getBoundsInScreen)
            check(cardBounds.contains(bounds)) { "Description overflows its card" }
            check(card.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            test.waitForIdleSync()
            check(selected) { "Showing the description broke lobby selection" }
            check(find("无描述") != null)
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                val file = java.io.File(context.getExternalFilesDir(null), "lobby-compatibility.png")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val marqueeWidth = mutableStateOf(100.dp)
            val marqueeText = mutableStateOf("欢迎")
            var marqueeBounds: androidx.compose.ui.geometry.Rect? = null
            test.runOnMainSync {
                host.setContent {
                    val view = LocalView.current
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                        Column(Modifier.offset(y = 64.dp).width(marqueeWidth.value).height(48.dp)
                            .background(androidx.compose.ui.graphics.Color.Black)
                            .onGloballyPositioned {
                                val origin = IntArray(2).also(view::getLocationOnScreen)
                                marqueeBounds = it.boundsInRoot().translate(androidx.compose.ui.geometry.Offset(origin[0].toFloat(), origin[1].toFloat()))
                            }) {
                            top.pmh13.mctier.ui.MarqueeText(marqueeText.value, androidx.compose.ui.graphics.Color.White)
                        }
                        }
                    }
                }
            }
            fun frame(): android.graphics.Bitmap {
                val bounds = marqueeBounds ?: error("Marquee not laid out")
                val screen = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                return android.graphics.Bitmap.createBitmap(screen, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt()).also { screen.recycle() }
            }
            fun expectMotion(expected: Boolean) {
                test.waitForIdleSync()
                SystemClock.sleep(2600)
                val first = frame()
                SystemClock.sleep(450)
                val second = frame()
                if ((!first.sameAs(second)) != expected) {
                    for ((name, bitmap) in listOf("first" to first, "second" to second)) {
                        java.io.File(context.getExternalFilesDir(null), "marquee-$name.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
                try { check((!first.sameAs(second)) == expected) { "Marquee motion mismatch: width=${marqueeWidth.value}, text=${marqueeText.value}, expected=$expected" } }
                finally { first.recycle(); second.recycle() }
            }
            expectMotion(false)
            test.runOnMainSync { marqueeText.value = "欢迎加入大厅，请遵守游戏规则" }
            expectMotion(true)
            test.runOnMainSync { marqueeWidth.value = 280.dp }
            expectMotion(false)
            test.runOnMainSync { marqueeWidth.value = 100.dp }
            expectMotion(true)
            result.putString("stream", "PASS: RTC initialize/mic toggle/cleanup preserve simulated call mode and volume; public lobby description wraps within 280dp card and selection still works; announcement short text stays still, overflowing text scrolls, widening stops and narrowing resumes scrolling (pixel comparison).\n")
        } catch (error: Throwable) {
            code = 1
            result.putString("stream", "FAIL: ${error.stackTraceToString()}")
        } finally {
            test.runOnMainSync {
                rtc.cleanup(); host?.setContent {}; host?.finish()
                top.pmh13.mctier.ui.applyAppLanguage(previousLanguage)
            }
            // Restore only the mode explicitly acquired by this test, never a real call.
            simulatedCall?.stop()
            simulatedCall?.release()
            if (previousMode == AudioManager.MODE_NORMAL) audio.mode = previousMode
            val editor = consent.edit()
            if (hadConsent) editor.putBoolean("agreed_v1", previousConsent) else editor.remove("agreed_v1")
            editor.commit()
            test.finish(code, result)
        }
    }
}
