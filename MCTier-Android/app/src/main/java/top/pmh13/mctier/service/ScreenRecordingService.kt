package top.pmh13.mctier.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.pmh13.mctier.MainActivity
import top.pmh13.mctier.recording.*
import top.pmh13.mctier.ui.L

/** User-started MediaProjection only. Never restarts capture without fresh system consent. */
class ScreenRecordingService : Service() {
    @Volatile private var engine: RecordingEngine? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> engine?.stop()
            "pause" -> engine?.pause()
            "start" -> {
                if (engine != null) return START_NOT_STICKY
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("consent", Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra("consent")
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                val options = RecordingOptions(intent.getIntExtra("resolution", 1080), intent.getIntExtra("fps", 60), intent.getIntExtra("bitrate", 16), intent.getBooleanExtra("systemAudio", false), intent.getBooleanExtra("microphone", false))
                try {
                    options.validate()
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, L("屏幕录制", "Screen recording"), NotificationManager.IMPORTANCE_LOW))
                    val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        (if (options.microphone && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) else 0
                    if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(false), type) else startForeground(ID, notification(false))
                    mutable.value = RecordingState("preparing")
                    val recorder = RecordingEngine(this, options, data) { value ->
                        val changed = mutable.value.phase != value.phase
                        mutable.value = value
                        if (changed) manager.notify(ID, notification(value.phase == "paused"))
                    }
                    engine = recorder
                    Thread({
                        val result = recorder.run()
                        engine = null
                        mutable.value = result
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }, "mctier-screen-recording").start()
                } catch (e: Exception) {
                    mutable.value = RecordingState(error = e.message ?: "无法启动录屏")
                    stopSelf()
                }
            }
            else -> if (engine == null) stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(paused: Boolean): Notification {
        fun command(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, ScreenRecordingService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(L("MCTier 屏幕录制", "MCTier screen recording"))
            .setContentText(if (paused) L("录制已暂停", "Recording paused") else L("正在录制 · 点击返回 MCTier", "Recording · Tap to return to MCTier"))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (paused) L("继续", "Resume") else L("暂停", "Pause"), command("pause"))
            .addAction(0, L("停止并保存", "Stop and save"), command("stop")).build()
    }
    override fun onDestroy() { engine?.stop(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "mctier_screen_recording"
        private const val ID = 4542
        private val mutable = MutableStateFlow(RecordingState())
        val state = mutable.asStateFlow()
        fun start(context: Context, data: Intent, options: RecordingOptions) {
            check(mutable.value.phase == "idle") { "已有录制正在进行" }
            mutable.value = RecordingState("preparing")
            try {
                context.startForegroundService(Intent(context, ScreenRecordingService::class.java).setAction("start")
                    .putExtra("consent", data).putExtra("resolution", options.resolution).putExtra("fps", options.fps)
                    .putExtra("bitrate", options.bitrate).putExtra("systemAudio", options.systemAudio).putExtra("microphone", options.microphone))
            } catch (e: Exception) { mutable.value = RecordingState(error = e.message ?: "录屏启动失败"); throw e }
        }
        fun command(context: Context, action: String) {
            if (mutable.value.phase != "idle") context.startService(Intent(context, ScreenRecordingService::class.java).setAction(action))
        }
    }
}
