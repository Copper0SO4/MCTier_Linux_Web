package top.pmh13.mctier

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.pmh13.mctier.ui.QuarkMobileLoginForm
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android WebView/Compose checks. Never sends SMS or submits credentials. */
internal class QuarkMediaChecks(private val test: Instrumentation) {
    private fun clearContent(host: MainActivity) {
        // setContent schedules a future composition. Native tracks/bitmaps must
        // outlive the old AndroidView's onRelease, not merely the main-thread call.
        val applied = CountDownLatch(1)
        test.runOnMainSync { host.setContent { androidx.compose.runtime.SideEffect { applied.countDown() } } }
        check(applied.await(5, TimeUnit.SECONDS)) { "UI teardown did not commit" }
        test.waitForIdleSync()
    }
    fun run(remote: Boolean = false) {
        val result = Bundle()
        var activity: MainActivity? = null
        var code = 0
        try {
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = activity
            if (remote) {
                repeat(2) { remoteFrame(host) }
                result.putString("stream", "PASS: remote control video is visible in the actual Compose overlay.\n")
                return
            }
            test.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        Dialog(onDismissRequest = {}) {
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                Text("Quark official phone login")
                                QuarkMobileLoginForm("instrumentation", onTicket = { error("Unexpected login ticket") }, onError = { android.util.Log.w("QuarkMediaCheck", it) })
                            }
                        }
                    }
                }
            }
            SystemClock.sleep(18000)
            val inspected = CountDownLatch(1)
            test.runOnMainSync {
                fun web(view: View): WebView? = if (view is WebView) view else (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { web(group.getChildAt(it)) } }
                val view = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::web)
                android.util.Log.i("QuarkMediaCheck", "WebView size=${view?.width}x${view?.height}, params=${view?.layoutParams?.width}x${view?.layoutParams?.height}")
                view?.evaluateJavascript("JSON.stringify({height:innerHeight,width:innerWidth,body:document.body.getBoundingClientRect().toJSON(),frame:document.querySelector('iframe')?.getBoundingClientRect().toJSON()})") {
                    android.util.Log.i("QuarkMediaCheck", "Layout: $it")
                    inspected.countDown()
                } ?: inspected.countDown()
            }
            inspected.await(3, TimeUnit.SECONDS)
            val output = File(test.targetContext.getExternalFilesDir(null), "media-checks").apply { mkdirs() }
            var bluePixels = 0
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "phone-login.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.blue(pixel) > 200 && android.graphics.Color.red(pixel) < 60 && android.graphics.Color.green(pixel) in 30..150) bluePixels++
                }
                bitmap.recycle()
            }
            // Older Android WebViews don't expose cross-origin iframe descendants
            // to UiAutomation. Check actual rendered pixels, not just DOM presence.
            check(bluePixels > 1000) { "Official login button is not rendered: bluePixels=$bluePixels" }
            checkQr(host, output)
            result.putString("stream", "PASS: official phone login paints in Android WebView ($bluePixels blue samples); compact QR decodes from the actual screenshot. No SMS sent.\n")
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}")
            code = 1
        } finally {
            test.runOnMainSync { activity?.finish() }
            test.finish(code, result)
        }
    }

    private fun checkQr(host: MainActivity, output: File) {
        val url = "https://su.quark.cn/4_eMHBJ?token=${"a".repeat(32)}&client_id=532&ssb=weblogin"
        val qr = top.pmh13.mctier.ui.createQuarkQr(url)
        test.runOnMainSync {
            host.setContent {
                MaterialTheme {
                    androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color(0xff222233)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { top.pmh13.mctier.ui.QuarkLoginQr(qr) }
                    }
                }
            }
        }
        SystemClock.sleep(800)
        val screenshot = requireNotNull(test.uiAutomation.takeScreenshot())
        File(output, "compact-qr.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = IntArray(screenshot.width * screenshot.height)
        screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
        val source = com.google.zxing.RGBLuminanceSource(screenshot.width, screenshot.height, pixels)
        val decoded = com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
        check(decoded.text == url) { "Compact QR cannot be scanned from the screenshot" }
        screenshot.recycle()
        clearContent(host)
        qr.recycle()
    }

    private fun remoteFrame(host: MainActivity) {
        val repository = MctierRepository.get(test.targetContext)
        val controllerField = MctierRepository::class.java.getDeclaredField("remoteControlController").apply { isAccessible = true }
        val previous = controllerField.get(repository)
        val controller = top.pmh13.mctier.network.RemoteControlController(test.targetContext, "render-check") {}
        val factory = top.pmh13.mctier.network.RemoteControlController::class.java.getDeclaredField("factory").apply { isAccessible = true }.get(controller) as org.webrtc.PeerConnectionFactory
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("test-picture", source)
        val frame = org.webrtc.JavaI420Buffer.allocate(640, 360).apply {
            // BT.601 green, to detect a black/covered surface from real screenshot pixels.
            listOf(dataY to 145, dataU to 54, dataV to 34).forEach { (buffer, value) ->
                while (buffer.hasRemaining()) buffer.put(value.toByte())
            }
        }
        try {
            top.pmh13.mctier.network.RemoteControlController::class.java.getDeclaredField("controllerVideoTrack").apply { isAccessible = true }.set(controller, track)
            controllerField.set(repository, controller)
            test.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            Text("Underlying app content")
                            top.pmh13.mctier.ui.RemoteControlControllerView(repository, "Renderer test")
                        }
                    }
                }
            }
            source.capturerObserver.onCapturerStarted(true)
            repeat(60) {
                frame.retain()
                val video = org.webrtc.VideoFrame(frame, 0, System.nanoTime())
                source.capturerObserver.onFrameCaptured(video)
                video.release()
                SystemClock.sleep(100)
            }
            val bitmap = requireNotNull(test.uiAutomation.takeScreenshot())
            val output = File(test.targetContext.getExternalFilesDir(null), "media-checks").apply { mkdirs() }
            File(output, "remote-frame.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            bitmap.recycle()
            check(android.graphics.Color.green(pixel) > 180 && android.graphics.Color.red(pixel) < 80) { "Video surface is black/covered: center=${Integer.toHexString(pixel)}" }
        } finally {
            clearContent(host)
            controllerField.set(repository, previous)
            controller.release()
            track.dispose()
            source.dispose()
            frame.release()
        }
    }
}
