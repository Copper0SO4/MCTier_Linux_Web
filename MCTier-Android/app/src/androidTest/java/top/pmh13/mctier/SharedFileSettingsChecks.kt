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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.pmh13.mctier.data.RemoteFileInfo
import top.pmh13.mctier.ui.RemoteFileSelectionBar
import top.pmh13.mctier.ui.FileShareDownloadFolderSetting
import top.pmh13.mctier.ui.L

/** Actual shared controls at narrow width; settings are restored after the check. */
internal class SharedFileSettingsChecks(private val test: Instrumentation) {
    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { hit -> return hit } }
            return null
        }
        return test.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(label: String) {
        val end = SystemClock.uptimeMillis() + 8000
        while (find(label) == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
        check(find(label) != null) { "Not visible: $label" }
    }
    private fun click(label: String) {
        waitFor(label)
        var node = find(label)!!
        while (!node.isClickable) node = node.parent ?: error("Not clickable: $label")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        test.waitForIdleSync()
    }
    fun run() {
        val output = Bundle()
        var code = 0
        var activity: MainActivity? = null
        val repository = MctierRepository.get(test.targetContext)
        val original = repository.state.value.settings
        try {
            check(repository.state.value.lobby == null) { "Leave the active lobby before running this fixture" }
            val files = listOf(RemoteFileInfo("a.txt", "a.txt"), RemoteFileInfo("b.txt", "b.txt"), RemoteFileInfo("folder", "folder", isDir = true))
            val selection = mutableStateOf(listOf("a.txt"))
            val visibleFiles = mutableStateOf(files)
            var downloaded = emptyList<RemoteFileInfo>()
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            test.runOnMainSync {
                activity!!.setContent { MaterialTheme { Column(Modifier.width(260.dp)) {
                    RemoteFileSelectionBar(visibleFiles.value, selection.value, { selection.value = it }, { downloaded = it })
                } } }
            }
            click(L("清空", "Clear"))
            waitFor(L("已选 0 个文件", "0 selected"))
            test.runOnMainSync { selection.value = listOf("a.txt") }
            click(L("全选", "Select all"))
            waitFor(L("已选 2 个文件", "2 selected"))
            check(selection.value.toSet() == setOf("a.txt", "b.txt"))
            click(L("下载选中", "Download selected"))
            check(downloaded.map { it.path }.toSet() == setOf("a.txt", "b.txt"))
            click(L("取消全选", "Deselect all"))
            waitFor(L("已选 0 个文件", "0 selected"))
            test.runOnMainSync { visibleFiles.value = listOf(files.last()) }
            test.waitForIdleSync()
            SystemClock.sleep(300)
            check(find(L("全选", "Select all")) == null)

            test.runOnMainSync {
                repository.updateSettings(original.copy(fileShareDownloadTreeUri = "content://fixture/tree/TestDownloads"))
                activity!!.setContent { MaterialTheme { Column(Modifier.width(260.dp)) {
                    val state by repository.state.collectAsState()
                    FileShareDownloadFolderSetting(state.settings, repository)
                } } }
            }
            waitFor("TestDownloads")
            check(test.targetContext.getSharedPreferences("mctier", 0).getString("fileShareDownloadTreeUri", "")!!.endsWith("TestDownloads"))
            click(L("恢复默认", "Reset"))
            waitFor(L("默认：应用下载目录 / MCTier", "Default: app download folder / MCTier"))
            check(repository.state.value.settings.fileShareDownloadTreeUri.isEmpty())
            check(test.targetContext.getSharedPreferences("mctier", 0).getString("fileShareDownloadTreeUri", "invalid") == "")
            output.putString("stream", "PASS: narrow selection toolbar selects files only, bulk download receives selected files, deselect and folder-only directory work; shared download preference displays and resets persistently.\n")
        } catch (error: Throwable) {
            code = 1; output.putString("stream", "FAIL: ${error.stackTraceToString()}")
        } finally {
            test.runOnMainSync { repository.updateSettings(original); activity?.setContent {}; activity?.finish() }
            test.finish(code, output)
        }
    }
}
