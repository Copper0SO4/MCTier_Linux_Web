package top.pmh13.mctier.ui

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.runtime.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VoiceMessagePlayer(data: String, mine: Boolean, initialDuration: Float = 0f, onLongClick: () -> Unit = {}) {
    var playing by remember(data) { mutableStateOf(false) }
    val player = remember(data) { MediaPlayer() }
    var ready by remember(data) { mutableStateOf(false) }
    var failed by remember(data) { mutableStateOf(false) }
    var positionMs by remember(data) { mutableIntStateOf(0) }
    var waveWidth by remember { mutableIntStateOf(1) }
    var durationMs by remember(data) { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                runCatching { if (player.isPlaying) player.pause() }
                playing = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) {
        runCatching {
            val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
            require(bytes.size <= 2 * 1024 * 1024)
            player.setDataSource(object : MediaDataSource() {
                override fun getSize(): Long = bytes.size.toLong()
                override fun close() { bytes.fill(0) }
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
            })
            player.setOnPreparedListener { durationMs = it.duration.coerceAtLeast(0); ready = true }
            player.setOnCompletionListener { playing = false; positionMs = 0 }
            player.setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
            player.prepareAsync()
        }.onFailure { failed = true }
        onDispose { player.release() }
    }
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            kotlinx.coroutines.delay(100)
        }
    }
    val enabled = ready && !failed
    val bubbleColor = if (mine) GrassGreen else PanelHigh
    val contentColor = if (mine) OnAccent else TextPrimary
    val progress = if (ready && player.duration > 0) positionMs.toFloat() / player.duration else 0f
    val duration = if (durationMs > 0) durationMs / 1000f else initialDuration
    Row(
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("voice-bubble").width(voiceBubbleWidth(duration).dp).height(40.dp).clip(RoundedCornerShape(14.dp)).background(bubbleColor).combinedClickable(onClick = {}, onLongClick = onLongClick).padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(30.dp).combinedClickable(enabled = enabled, onClick = {
            runCatching { if (playing) player.pause() else player.start(); playing = !playing }.onFailure { failed = true }
        }, onLongClick = onLongClick), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(contentColor.copy(alpha = .10f)).testTag("voice-play-background"), contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (playing) L("暂停语音", "Pause voice") else L("播放语音", "Play voice"), tint = contentColor, modifier = Modifier.size(16.dp))
            }
        }
        Row(
            Modifier.weight(1f).height(20.dp).onSizeChanged { waveWidth = maxOf(1, it.width) }
                .pointerInteropFilter { event ->
                    if (!enabled) return@pointerInteropFilter true
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN || event.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                        val fraction = (event.x / waveWidth).coerceIn(0f, 1f)
                        positionMs = (player.duration * fraction).toInt()
                        player.seekTo(positionMs)
                    }
                    true
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(14) { index ->
                Box(
                    Modifier.width(3.dp).height((7 + (index * 7 % 12)).dp).clip(RoundedCornerShape(2.dp))
                        .background(contentColor.copy(alpha = if ((index + 1) / 14f <= progress) .9f else .3f)),
                )
            }
        }
        Text(if (failed) "--" else "${kotlin.math.ceil(duration.toDouble()).toInt().coerceAtLeast(1)}s", modifier = Modifier.widthIn(min = 24.dp), maxLines = 1, color = contentColor.copy(alpha = .86f), fontSize = 11.sp)
    }
}
