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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    fun run(remote: Boolean = false, buttons: Boolean = false, ux: Boolean = false) {
        val result = Bundle()
        var activity: MainActivity? = null
        var code = 0
        try {
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = activity
            if (ux) {
                checkSettingsUx(host)
                result.putString("stream", "PASS: light/dark white switch thumbs, circular selected color mark, Chinese language label in English, and separate scrollable agreement dialogs with acknowledgement in settings and first-use screen. Screenshots saved.\n")
                return
            }
            if (buttons) {
                checkLoginButtons(host)
                result.putString("stream", "PASS: actual login buttons and QR/phone action labels are centered horizontally and vertically at 320dp and 360dp; phone mode click switches the action. Screenshots saved.\n")
                return
            }
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


    private fun checkSettingsUx(host: MainActivity) {
        val settings = MctierRepository.get(test.targetContext).state.value.settings
        val output = File(test.targetContext.getExternalFilesDir(null), "media-checks").apply { mkdirs() }
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
            val result = arrayListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(n: android.view.accessibility.AccessibilityNodeInfo) { if (!n.refresh()) return; result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
            test.uiAutomation.windows.forEach { it.root?.let(::visit) }; return result
        }
        fun click(label: String) {
            var target = nodes().firstOrNull { it.text?.toString() == label } ?: error("Missing $label")
            while (!target.isClickable) target = target.parent ?: error("Not clickable $label")
            check(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(500)
        }
        try {
            for (mode in listOf("light", "dark")) {
                test.runOnMainSync {
                    top.pmh13.mctier.ui.applyAppTheme(mode, settings.themePrimary)
                    top.pmh13.mctier.ui.applyAppLanguage("en")
                    host.setContent {
                        MaterialTheme(colorScheme = if(mode == "light") androidx.compose.material3.lightColorScheme() else androidx.compose.material3.darkColorScheme()) {
                            androidx.compose.material3.Surface {
                                Column(Modifier.fillMaxSize().padding(22.dp)) {
                                    top.pmh13.mctier.ui.ThemeSettingsSection(settings.copy(language = "en", themeMode = mode)) {}
                                    Row {
                                        androidx.compose.material3.Switch(true, {}, modifier = Modifier.semantics { contentDescription = "ux-switch-on" }, colors = top.pmh13.mctier.ui.switchColors())
                                        androidx.compose.material3.Switch(false, {}, modifier = Modifier.semantics { contentDescription = "ux-switch-off" }, colors = top.pmh13.mctier.ui.switchColors())
                                    }
                                    top.pmh13.mctier.ui.ColorSelectionMark()
                                }
                            }
                        }
                    }
                }
                SystemClock.sleep(900)
                check(nodes().any { it.text?.toString() == "简体中文" })
                check(nodes().none { it.text?.toString() == "Simplified Chinese" })
                val bitmap = test.uiAutomation.takeScreenshot() ?: error("No screenshot")
                try {
                    File(output, "settings-$mode.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    var left=bitmap.width; var right=0; var top=bitmap.height; var bottom=0
                    for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) if(bitmap.getPixel(x,y)==0xFF16311F.toInt()) {
                        left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)
                    }
                    check(right > left && kotlin.math.abs((right-left)-(bottom-top)) <= 1) { "Selection background is not a circle: ${right-left} x ${bottom-top}" }
                    val switches=nodes().filter { it.contentDescription?.toString()?.startsWith("ux-switch-")==true }
                    check(switches.size == 2) { "Missing switch semantics" }
                    for(node in switches) {
                        val rect=android.graphics.Rect().also(node::getBoundsInScreen)
                        val center=rect.centerY()
                        val pixels=(rect.left until rect.right).count { x -> bitmap.getPixel(x,center) and 0xFFFFFF == 0xFFFFFF }
                        check(pixels >= 12 * test.targetContext.resources.displayMetrics.density) { "Thumb is not white in $mode" }
                    }
                } finally { bitmap.recycle() }
            }
            for (firstUse in listOf(false, true)) {
                test.runOnMainSync {
                    top.pmh13.mctier.ui.applyAppLanguage("zh")
                    host.setContent {
                        MaterialTheme {
                            if (firstUse) top.pmh13.mctier.ui.ConsentScreen(onAgree = { error("Reading must not grant consent") }, onDisagree = {})
                            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { top.pmh13.mctier.ui.ComplianceLinksSection() }
                        }
                    }
                }
                SystemClock.sleep(600)
                for(label in listOf("隐私政策", "用户协议", "权限用途说明", "免责声明")) {
                    click(if(firstUse) "《$label》" else label)
                    val bodies=nodes().filter { it.text?.toString()?.contains("更新日期：2026年10月3日")==true }
                    check(bodies.size == 1) { "Expected one document in the reading dialog" }
                    val button = nodes().first { it.text?.toString() == "我已阅读" }
                    val bounds = android.graphics.Rect().also(button::getBoundsInScreen)
                    check(button.isVisibleToUser && bounds.bottom < test.targetContext.resources.displayMetrics.heightPixels) { "Acknowledgement is clipped" }
                    check(test.uiAutomation.windows.count { it.root != null } >= 2) { "Reader is not a separate window" }
                    test.uiAutomation.takeScreenshot()?.let { bitmap -> File(output,"agreement-${if(firstUse) "first" else "settings"}-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
                    click("我已阅读")
                    check(nodes().none { it.text?.toString() == "我已阅读" }) { "Acknowledgement did not close reader" }
                }
            }
        } finally { test.runOnMainSync { top.pmh13.mctier.ui.applyAppTheme(settings.themeMode,settings.themePrimary);top.pmh13.mctier.ui.applyAppLanguage(settings.language) } }
    }

    private fun checkLoginButtons(host: MainActivity) {
        check(!top.pmh13.mctier.network.QuarkSupport.get(test.targetContext).view.value.loggedIn) { "Login layout check requires no real Quark login" }
        val output = File(test.targetContext.getExternalFilesDir(null), "media-checks").apply { mkdirs() }
        val density = test.targetContext.resources.displayMetrics.density
        fun find(label: String): android.view.accessibility.AccessibilityNodeInfo {
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                node.refresh()
                if (node.text?.toString() == label) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { hit -> return hit } }
                return null
            }
            var node = test.uiAutomation.windows.firstNotNullOfOrNull { it.root?.let { root -> root.refresh(); visit(root) } } ?: error("Missing button: $label")
            // Text semantics can be a child of the clickable chip/button semantics.
            while (!node.isClickable) node = node.parent ?: error("Missing clickable parent: $label")
            return node
        }
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            for (width in listOf(320, 360)) {
                test.runOnMainSync {
                    top.pmh13.mctier.ui.applyAppLanguage("zh")
                    host.setContent {
                        // Match the persisted app palette; do not combine light panels with dark chip text colors.
                        MaterialTheme(colorScheme = if (MctierRepository.get(test.targetContext).state.value.settings.themeMode == "light")
                            androidx.compose.material3.lightColorScheme() else androidx.compose.material3.darkColorScheme()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                androidx.compose.material3.Surface(Modifier.width(width.dp)) {
                                    Column(Modifier.verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(16.dp)) {
                                        top.pmh13.mctier.ui.QuarkSupportCard()
                                    }
                                }
                            }
                        }
                    }
                }
                SystemClock.sleep(1000)
                for (mobile in listOf(false, true)) {
                    if (mobile) {
                        check(find("手机号登录").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                        SystemClock.sleep(600)
                        test.waitForIdleSync()
                        test.uiAutomation.waitForIdle(500, 5000)
                    }
                    val bitmap = requireNotNull(test.uiAutomation.takeScreenshot())
                    try {
                        File(output, "login-buttons-$width-${if (mobile) "phone" else "qr"}.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        for (label in listOf("扫码登录", "手机号登录", if (mobile) "开始手机号登录" else "获取登录二维码")) {
                            val rect = android.graphics.Rect().also { find(label).getBoundsInScreen(it) }
                            check(rect.left >= 0 && rect.right <= bitmap.width && rect.top >= 0 && rect.bottom <= bitmap.height) { "Clipped button: $label $rect" }
                            val inset = (8 * density).toInt()
                            val background = bitmap.getPixel(rect.left + inset, rect.centerY())
                            val ink = android.graphics.Rect()
                            // Ignore the chip outline and rounded corners; measure the painted glyphs.
                            for (y in rect.centerY() - rect.height() / 4 until rect.centerY() + rect.height() / 4) {
                                for (x in rect.left + inset until rect.right - inset) {
                                    val pixel = bitmap.getPixel(x, y)
                                    val contrast = maxOf(kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(background)),
                                        kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(background)),
                                        kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(background)))
                                    if (contrast > 60) ink.union(x, y, x + 1, y + 1)
                                }
                            }
                            check(!ink.isEmpty) { "No visible text: $label" }
                            check(kotlin.math.abs(ink.exactCenterX() - rect.exactCenterX()) <= 2 * density &&
                                kotlin.math.abs(ink.exactCenterY() - rect.exactCenterY()) <= 3 * density) { "Text not centered: $label button=$rect ink=$ink" }
                        }
                    } finally { bitmap.recycle() }
                }
                clearContent(host)
            }
        } finally {
            test.runOnMainSync { top.pmh13.mctier.ui.applyAppLanguage(MctierRepository.get(test.targetContext).state.value.settings.language) }
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
