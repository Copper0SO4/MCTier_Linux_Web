package top.pmh13.mctier

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import kotlinx.serialization.json.Json
import okhttp3.*
import org.json.JSONObject
import org.webrtc.PeerConnection
import top.pmh13.mctier.data.SignalingEnvelope
import top.pmh13.mctier.network.RemoteControlController
import top.pmh13.mctier.ui.RemoteControlControllerView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native Windows capture -> real WebRTC -> Android decoder -> production UI. */
internal class RemoteDesktopCheck(private val test: Instrumentation) {
    fun run() {
        val result = Bundle()
        val repository = MctierRepository.get(test.targetContext)
        val field = MctierRepository::class.java.getDeclaredField("remoteControlController").apply { isAccessible = true }
        val previous = field.get(repository)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val client = OkHttpClient()
        var activity: MainActivity? = null
        var socket: WebSocket? = null
        var failure: String? = null
        var code = 1
        val ready = CountDownLatch(1)
        val controller = RemoteControlController(test.targetContext, "android-check") {
            socket?.send(json.encodeToString(SignalingEnvelope.serializer(), it))
        }
        try {
            field.set(repository, controller)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = activity
            test.runOnMainSync {
                host.setContent { MaterialTheme { RemoteControlControllerView(repository, "Windows actual screen") } }
            }
            socket = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:47839/android").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"type\":\"test-ready\"}") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure = t.toString(); ready.countDown() }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (JSONObject(text).optString("type") == "test-start") {
                        host.runOnUiThread { controller.requestControl("desktop-check", "Windows actual screen"); ready.countDown() }
                    } else {
                        val message = json.decodeFromString(SignalingEnvelope.serializer(), text)
                        host.runOnUiThread { controller.handleSignal(message) }
                    }
                }
            })
            check(ready.await(90, TimeUnit.SECONDS)) { "Windows test peer did not join" }
            check(failure == null) { failure.orEmpty() }
            val pcField = RemoteControlController::class.java.getDeclaredField("pc").apply { isAccessible = true }
            var frames = 0L
            var bytes = 0L
            val deadline = SystemClock.uptimeMillis() + 30000
            while (SystemClock.uptimeMillis() < deadline) {
                val pc = pcField.get(controller) as? PeerConnection
                if (pc != null) {
                    val statsReady = CountDownLatch(1)
                    pc.getStats { report ->
                        report.statsMap.values.filter { it.type == "inbound-rtp" && (it.members["kind"] == "video" || it.members["mediaType"] == "video") }.forEach {
                            frames = (it.members["framesDecoded"] as? Number)?.toLong() ?: 0
                            bytes = (it.members["bytesReceived"] as? Number)?.toLong() ?: 0
                        }
                        statsReady.countDown()
                    }
                    statsReady.await(2, TimeUnit.SECONDS)
                }
                controller.sendInput("[{\"kind\":\"move\",\"x\":0.5,\"y\":0.5}]")
                if (frames >= 10) break
                SystemClock.sleep(400)
            }
            check(frames >= 10 && bytes > 0) { "No decoded Windows video: frames=$frames bytes=$bytes" }
            // Allow the actual SurfaceView and landscape layout to present frames.
            SystemClock.sleep(2000)
            val bitmap = requireNotNull(test.uiAutomation.takeScreenshot())
            val colors = mutableSetOf<Int>()
            var visible = 0
            var samples = 0
            for (y in bitmap.height / 3 until bitmap.height * 3 / 4 step 8) {
                for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 8) {
                    val pixel = bitmap.getPixel(x, y)
                    colors += pixel and 0x00f0f0f0
                    if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 30) visible++
                    samples++
                }
            }
            val output = File(test.targetContext.getExternalFilesDir(null), "media-checks").apply { mkdirs() }
            File(output, "windows-remote-live.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            check(colors.size > 8 && visible > samples / 20) { "Decoded video is not visible: colors=${colors.size} visible=$visible/$samples" }
            val report = JSONObject().put("type", "test-complete").put("ok", true).put("framesDecoded", frames).put("bytesReceived", bytes).put("visibleSamples", visible).put("colors", colors.size)
            socket.send(report.toString())
            SystemClock.sleep(1500)
            result.putString("stream", "PASS: $report\n")
            code = 0
        } catch (error: Throwable) {
            socket?.send(JSONObject().put("type", "test-complete").put("ok", false).put("error", error.toString()).toString())
            result.putString("stream", "FAIL: ${error.stackTraceToString()}")
        } finally {
            test.runOnMainSync { activity?.setContent {} }
            test.waitForIdleSync()
            controller.release()
            field.set(repository, previous)
            socket?.close(1000, "finished")
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            test.runOnMainSync { activity?.finish() }
            test.finish(code, result)
        }
    }
}
