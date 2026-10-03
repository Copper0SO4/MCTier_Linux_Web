package top.pmh13.mctier.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.pmh13.mctier.network.screenCaptureIntent
import top.pmh13.mctier.recording.RecordingOptions
import top.pmh13.mctier.service.ScreenRecordingService

@Composable
internal fun ScreenRecordingPanel(captureBusy: Boolean = false) {
    val context = LocalContext.current
    val state by ScreenRecordingService.state.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(RecordingOptions()) }
    var error by remember { mutableStateOf("") }
    var requesting by remember { mutableStateOf(false) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        requesting = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching { ScreenRecordingService.start(context, result.data!!, options) }.onFailure { error = it.message.orEmpty() }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) capture.launch(screenCaptureIntent(context)) else { requesting = false; error = L("未授予所需权限，请允许保存视频；录音权限可在关闭声音后跳过", "Required permission denied. Allow video storage; audio permission is optional when sound is disabled.") }
    }
    val busy = state.phase != "idle" || requesting
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(L("记录精彩，留住瞬间", "Capture moments, keep them forever"), color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(L("录像保存为 MP4。", "Save an MP4 locally."), color = TextPrimary.copy(alpha = .65f), fontSize = 12.sp)
        if (state.phase == "idle") {
        RecordingChoice(L("分辨率上限", "Resolution"), options.resolution, listOf(720, 1080, 1440, 2160), "p", busy) { options = options.copy(resolution = it) }
        RecordingChoice(L("帧率", "Frame rate"), options.fps, listOf(30, 60), " FPS", busy) { options = options.copy(fps = it) }
        RecordingChoice(L("视频码率", "Video bitrate"), options.bitrate, listOf(4, 8, 16, 32), " Mbps", busy) { options = options.copy(bitrate = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("内部声音", "Device audio"), color = TextPrimary, modifier = Modifier.weight(1f))
            Switch(options.systemAudio, { options = options.copy(systemAudio = it) }, colors = switchColors(), enabled = !busy && Build.VERSION.SDK_INT >= 29, modifier = Modifier.semantics { contentDescription = L("录制内部声音", "Record device audio") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("麦克风", "Microphone"), color = TextPrimary, modifier = Modifier.weight(1f))
            Switch(options.microphone, { options = options.copy(microphone = it) }, colors = switchColors(), enabled = !busy, modifier = Modifier.semantics { contentDescription = L("录制麦克风", "Record microphone") })
        }
        Text(if (Build.VERSION.SDK_INT >= 34) L("下一步可在系统选择器中选择整个屏幕或单个应用。", "Next, choose the entire screen or an app in the system picker.") else L("当前系统支持整个屏幕；单应用录制需要 Android 14 或更高版本。", "This system supports the entire screen; app recording requires Android 14+."), color = TextPrimary.copy(alpha = .65f), fontSize = 12.sp)
        Text(L("内部声音需要 Android 10+ 且被录应用允许录音；受保护内容可能无声或黑屏。使用扬声器和麦克风同时录音可能产生回声。", "Device audio requires Android 10+ and permission from the captured app. Protected content may be silent or black; speakers and microphone may cause echo."), color = TextPrimary.copy(alpha = .6f), fontSize = 12.sp)
        } else {
            Text(L("录像正在本机保存，暂停和停止按钮始终可用。", "Recording locally. Pause or stop at any time."), color = TextPrimary.copy(alpha = .7f), fontSize = 12.sp)
        }
        if (state.phase != "idle") {
            val label = when (state.phase) { "paused" -> L("已暂停", "Paused"); "saving" -> L("正在保存", "Saving"); "preparing" -> L("正在准备", "Preparing"); else -> L("正在录制", "Recording") }
            Text("$label  %02d:%02d".format(state.seconds / 60, state.seconds % 60), color = GrassGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ScreenRecordingService.command(context, "pause") }, colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentText), enabled = state.phase in listOf("recording", "paused")) { Text(if (state.phase == "paused") L("继续", "Resume") else L("暂停", "Pause")) }
                Button(onClick = { ScreenRecordingService.command(context, "stop") }, enabled = state.phase != "saving", colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = androidx.compose.ui.graphics.Color.White)) { Text(L("停止并保存", "Stop and save")) }
            }
        } else {
            if (captureBusy) Text(L("请先停止屏幕共享或远程被控，再开始录屏。", "Stop screen sharing or remote capture before recording."), color = TextPrimary)
            Button(modifier = Modifier.fillMaxWidth(), enabled = !requesting && !captureBusy, onClick = {
                error = ""; requesting = true
                val required = buildList { if (options.microphone || options.systemAudio) add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }; if (required.isNotEmpty()) permission.launch(required.toTypedArray()) else capture.launch(screenCaptureIntent(context))
            }, colors = ButtonDefaults.buttonColors(containerColor = GrassGreen)) { Text(L("选择画面并开始录制", "Choose source and record")) }
        }
        if (error.isNotBlank() || state.error.isNotBlank()) Text(error.ifBlank { state.error }, color = DangerRed, fontSize = 12.sp)
        if (state.uri.isNotEmpty()) Text(L("录像已保存 · Movies/MCTier", "Saved · Movies/MCTier"), color = GrassGreen, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(L("关闭面板继续录制，可从通知栏暂停或停止。", "Recording continues when this panel closes. Pause or stop from the notification."), fontSize = 11.sp, color = TextPrimary.copy(alpha = .6f), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun RecordingChoice(label: String, value: Int, values: List<Int>, unit: String, disabled: Boolean, select: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, modifier = Modifier.weight(1f))
        Box {
            TextButton(enabled = !disabled, onClick = { open = true }) { Text("$value$unit", color = GrassGreen) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = PanelHigh) {
                values.forEach { option -> DropdownMenuItem(text = { Text("$option$unit", color = TextPrimary) }, onClick = { open = false; select(option) }) }
            }
        }
    }
}
