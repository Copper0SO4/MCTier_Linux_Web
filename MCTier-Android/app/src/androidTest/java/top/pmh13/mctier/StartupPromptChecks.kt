package top.pmh13.mctier

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import top.pmh13.mctier.data.AvailableUpdate
import top.pmh13.mctier.data.VersionAlert
import top.pmh13.mctier.network.QuarkSupport
import top.pmh13.mctier.network.QuarkSupportView
import top.pmh13.mctier.ui.StartupPrompts

/** Real prompt host with in-memory update/account fixtures; no sign-in, transfer or download. */
internal class StartupPromptChecks(private val test: Instrumentation) {
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = arrayListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) {
            if (!n.refresh()) return
            result += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
        }
        test.uiAutomation.windows.forEach { it.root?.let(::visit) }
        return result
    }
    private fun visible(text: String) = nodes().any { it.text?.toString() == text && it.isVisibleToUser }
    private fun waitFor(text: String) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!visible(text) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(visible(text)) { "Missing prompt: $text; visible=${nodes().mapNotNull { it.text }}" }
    }
    private fun click(text: String) {
        waitFor(text)
        var n = nodes().first { it.text?.toString() == text && it.isVisibleToUser }
        while (!n.isClickable) n = n.parent ?: error("Not clickable: $text")
        check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(350)
    }
    @Suppress("UNCHECKED_CAST")
    fun run() {
        val result = Bundle()
        var code = 0
        var host: MainActivity? = null
        // Prevent the test Activity from starting real sponsorship or auto-join work.
        val consent = test.targetContext.getSharedPreferences("mctier_compliance", 0)
        val hadConsentKey = consent.contains("agreed_v1")
        val oldConsent = consent.getBoolean("agreed_v1", false)
        consent.edit().putBoolean("agreed_v1", false).commit()
        val repository = MctierRepository.get(test.targetContext)
        val state = MctierRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(repository) as MutableStateFlow<MctierUiState>
        val original = state.value
        val quark = QuarkSupport.get(test.targetContext)
        val quarkState = QuarkSupport::class.java.getDeclaredField("state").apply { isAccessible = true }.get(quark) as MutableStateFlow<QuarkSupportView>
        val originalQuark = quarkState.value
        try {
            check(original.lobby == null) { "Leave the real lobby before running the prompt fixture" }
            test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            host = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            fun update(change: (MctierUiState) -> MctierUiState) {
                test.runOnMainSync { state.value = change(state.value) }
                SystemClock.sleep(350)
            }
            test.runOnMainSync {
                top.pmh13.mctier.ui.applyAppLanguage("zh")
                state.value = original.copy(startupUpdateChecked = false, versionError = null, updateAvailable = null)
                quarkState.value = QuarkSupportView(ready = true)
                host.setContent {
                    val current by state.collectAsState()
                    var support by remember { mutableStateOf(false) }
                    MaterialTheme { StartupPrompts(current, repository, false, support, { support = it }, {}) }
                }
            }
            SystemClock.sleep(700)
            check(!visible("免费持续支持 MCTier")) { "Sponsor appeared before the version check settled" }
            val rejected = VersionAlert("1.0.0", "99.0.0", "https://mctier.pmhs.top")
            update { it.copy(versionError = rejected, startupUpdateChecked = true, updateAvailable = AvailableUpdate("99.0.0", listOf("Fixture update"))) }
            waitFor("需要更新 MCTier")
            check(!visible("发现新版本 v99.0.0") && !visible("免费持续支持 MCTier"))
            click("稍后处理")
            waitFor("发现新版本 v99.0.0")
            check(!visible("免费持续支持 MCTier"))
            click("稍后")
            waitFor("免费持续支持 MCTier")
            update { it.copy(versionError = rejected) }
            waitFor("需要更新 MCTier")
            check(!visible("免费持续支持 MCTier"))
            click("稍后处理")
            waitFor("免费持续支持 MCTier")
            click("下次一定")
            update { it.copy(versionError = rejected) }
            click("稍后处理")
            check(!visible("免费持续支持 MCTier")) { "Dismissed sponsor invitation repeated" }
            result.putString("stream", "PASS: real Compose startup host waits for update check; mandatory > optional > sponsorship; skip resumes sponsorship; late mandatory preempts and resumes; dismissed invitation stays dismissed. No credentials changed.\n")
        } catch (error: Throwable) {
            code = 1
            result.putString("stream", "FAIL: ${error.stackTraceToString()}")
        } finally {
            test.runOnMainSync {
                host?.setContent { }
                host?.finish()
                state.value = original
                quarkState.value = originalQuark
                top.pmh13.mctier.ui.applyAppLanguage(original.settings.language)
            }
            val editor = consent.edit()
            if (hadConsentKey) editor.putBoolean("agreed_v1", oldConsent) else editor.remove("agreed_v1")
            editor.commit()
            test.finish(code, result)
        }
    }
}
