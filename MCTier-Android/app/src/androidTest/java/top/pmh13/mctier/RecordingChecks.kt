package top.pmh13.mctier

import android.app.Instrumentation
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.pmh13.mctier.ui.ScreenRecordingPanel
import top.pmh13.mctier.service.ScreenRecordingService
import java.io.File

internal class RecordingChecks(private val test: Instrumentation) {
    private fun key(code: Int) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input keyevent $code")).use { it.readBytes() }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { if (!n.refresh()) return; result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        test.uiAutomation.windows.forEach { it.root?.let(::visit) }
        return result
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 18000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(100) }
        error("Timed out: $label; state=${ScreenRecordingService.state.value}; labels=${nodes().map { it.text ?: it.contentDescription }}")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        waitFor(label) {
            node = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (node == null) {
                if (label == "屏幕录制") nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                else nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            node != null
        }
        var target = node!!
        while (!target.isClickable) target = target.parent ?: error("Not clickable: $label")
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(300)
    }
    fun run() {
        val result = Bundle(); var code = 0; var activity: MainActivity? = null
        var tone: android.media.AudioTrack? = null
        val reports = ArrayList<String>()
        try {
            test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            test.uiAutomation.grantRuntimePermission(test.targetContext.packageName, android.Manifest.permission.RECORD_AUDIO)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = activity
            val repository = MctierRepository.get(test.targetContext)
            test.runOnMainSync { host.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                top.pmh13.mctier.ui.RoomToolsDialog(repository.state.value, repository, {}, {}, {}, false, {}, {})
            } } }
            waitFor("recording is the default tab") { nodes().any { it.text?.toString() == "记录精彩，留住瞬间" } }
            val initialLabels = nodes().map { it.text?.toString() }.toSet()
            check(initialLabels.containsAll(listOf("记录精彩，留住瞬间", "60 FPS", "16 Mbps"))) { "Recording defaults: $initialLabels" }
            val uiFolder = File(test.targetContext.getExternalFilesDir(null), "recording-checks").apply { mkdirs() }
            test.uiAutomation.takeScreenshot()?.let { bitmap -> File(uiFolder, "recording-defaults.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            click("选择画面并开始录制")
            waitFor("consent before cancel") { nodes().any { it.text?.toString() in listOf("立即开始", "Start now", "START NOW", "开始录制", "开始") } }
            click("取消")
            waitFor("cancel returns to panel") { nodes().any { it.text?.toString() == "选择画面并开始录制" } }
            check(ScreenRecordingService.state.value.phase == "idle")
            tone = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(96000).build()
            val samples = ShortArray(48000) { (kotlin.math.sin(it * 2.0 * Math.PI * 440 / 48000) * 1500).toInt().toShort() }
            tone.write(samples, 0, samples.size); tone.setLoopPoints(0, samples.size, -1); tone.play()
            for ((system, mic) in listOf(false to false, true to false, false to true, true to true)) {
                nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                SystemClock.sleep(300)
                // Previous combinations: none -> system -> mic -> both.
                if (system && !mic || !system && mic) click("录制内部声音")
                if (!system && mic) click("录制麦克风")
                if (system && mic) click("录制内部声音")
                click("选择画面并开始录制")
                waitFor("system projection consent") { nodes().any { it.text?.toString() in listOf("立即开始", "Start now", "START NOW", "开始录制", "开始") } }
                val label = nodes().first { it.text?.toString() in listOf("立即开始", "Start now", "START NOW", "开始录制", "开始") }.text.toString()
                click(label)
                waitFor("recording") { ScreenRecordingService.state.value.phase == "recording" }
                SystemClock.sleep(2300)
                if (mic) {
                    val sender = top.pmh13.mctier.network.AndroidRtcController(test.targetContext)
                    val receiver = top.pmh13.mctier.network.AndroidRtcController(test.targetContext)
                    var peer: org.webrtc.PeerConnection? = null
                    try {
                        test.runOnMainSync {
                            sender.initialize("recording-z") { receiver.handleSignal(it) }
                            receiver.initialize("recording-a") { sender.handleSignal(it) }
                            sender.setMicEnabled(true)
                            sender.connectToPlayer("recording-a")
                            peer = sender.ensurePeer("recording-a")
                        }
                        fun packets(): Long {
                            val done = java.util.concurrent.CountDownLatch(1)
                            var value = 0L
                            test.runOnMainSync { peer!!.getStats { stats ->
                                value = stats.statsMap.values.filter { it.type == "outbound-rtp" && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
                                    .sumOf { (it.members["packetsSent"] as? Number)?.toLong() ?: 0L }
                                done.countDown()
                            } }
                            check(done.await(3, java.util.concurrent.TimeUnit.SECONDS)); return value
                        }
                        waitFor("voice call sends audio while recording") { packets() > 10 }
                        val before = packets()
                        var release: (() -> Unit)? = null
                        test.runOnMainSync { release = sender.suspendLobbyVoice() }
                        val message = top.pmh13.mctier.audio.VoiceMessageRecorder(test.targetContext)
                        try {
                            message.start(); SystemClock.sleep(1000)
                            check(message.finish(false) != null) { "Voice message failed during screen recording" }
                        } finally { message.finish(true); test.runOnMainSync { release?.invoke() } }
                        waitFor("call resumes after voice message") { packets() > before + 10 }
                        check(ScreenRecordingService.state.value.phase == "recording")
                        reports += "WebRTC sent audio before/after a real encoded voice message while screen recording"
                    } finally { test.runOnMainSync { sender.cleanup(); receiver.cleanup() } }
                }
                click("暂停")
                waitFor("paused") { ScreenRecordingService.state.value.phase == "paused" }
                val pausedAt = ScreenRecordingService.state.value.seconds
                SystemClock.sleep(1200)
                check(ScreenRecordingService.state.value.seconds <= pausedAt + 1)
                click("继续")
                waitFor("resumed") { ScreenRecordingService.state.value.phase == "recording" }
                SystemClock.sleep(2300)
                val folder = File(test.targetContext.getExternalFilesDir(null), "recording-checks").apply { mkdirs() }
                test.uiAutomation.takeScreenshot()?.let { bitmap -> File(folder, "recording-$system-$mic.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                if (system && mic) {
                    click("暂停")
                    waitFor("paused before background") { ScreenRecordingService.state.value.phase == "paused" }
                    key(android.view.KeyEvent.KEYCODE_HOME)
                    SystemClock.sleep(1000)
                    check(ScreenRecordingService.state.value.phase == "paused")
                    val notification = test.targetContext.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == 4542 }.notification
                    notification.actions.single { it.title.toString() == "停止并保存" }.actionIntent.send()
                } else click("停止并保存")
                waitFor("saved") { ScreenRecordingService.state.value.phase == "idle" }
                val state = ScreenRecordingService.state.value
                check(state.error.isBlank() && state.uri.isNotEmpty()) { state.toString() }
                val uri = android.net.Uri.parse(state.uri)
                val extractor = MediaExtractor()
                extractor.setDataSource(test.targetContext, uri, null)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString("mime") }
                check(formats.contains("video/avc"))
                check(formats.any { it?.startsWith("audio/") == true } == (system || mic)) { formats.toString() }
                if (system || mic) {
                    extractor.selectTrack(formats.indexOfFirst { it?.startsWith("audio/") == true })
                    var previous = -1L
                    while (extractor.sampleTime >= 0) {
                        val time = extractor.sampleTime
                        check(previous < 0 || time > previous && time - previous < 250000) { "Audio pause gap or invalid timestamps: $previous -> $time" }
                        previous = time
                        if (!extractor.advance()) break
                    }
                }
                extractor.release()
                val metadata = MediaMetadataRetriever()
                metadata.setDataSource(test.targetContext, uri)
                val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                check(duration in 3500..45000) { "Bad recording duration: $duration" }
                val frame = metadata.getFrameAtTime(1500000) ?: error("MP4 frame cannot be decoded")
                check(frame.width >= 2 && frame.height >= 2)
                File(folder, "video-$system-$mic.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle(); metadata.release()
                reports += "system=$system mic=$mic: $formats, ${duration}ms, decoded frame, pause/resume, saved $uri"
            }
            result.putString("stream", "PASS: ${reports.joinToString("; ")}\n")
        } catch (e: Throwable) { code = 1; result.putString("stream", "FAIL: ${e.stackTraceToString()}") }
        finally { tone?.stop(); tone?.release(); ScreenRecordingService.command(test.targetContext, "stop"); activity?.let { test.runOnMainSync { it.finish() } } }
        test.finish(code, result)
    }
}
