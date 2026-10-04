package top.pmh13.mctier.audio

import top.pmh13.mctier.recording.RecordingMicrophone
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Compress while recording so releasing the microphone only finalizes the container. */
class VoiceMessageRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var mime = "audio/mp4"
    private var started = 0L
    @Volatile private var completedByLimit = false
    @Volatile private var recordingFailed = false
    val seconds: Double get() = if (recorder == null) 0.0 else ((SystemClock.elapsedRealtime() - started) / 1000.0).coerceAtMost(60.0)

    @Synchronized
    fun start() {
        check(recorder == null)
        RecordingMicrophone.acquirePriority(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching { startCodec(true) }.isSuccess) return
            startCodec(false)
        } catch (e: Exception) { RecordingMicrophone.releasePriority(this); throw e }
    }

    @Suppress("DEPRECATION")
    private fun startCodec(opus: Boolean) {
        val file = File.createTempFile("voice-recording-", if (opus) ".ogg" else ".m4a", context.cacheDir)
        val native = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder() }
            catch (error: Exception) { file.delete(); throw error }
        try {
            completedByLimit = false
            recordingFailed = false
            native.setOnErrorListener { _, _, _ -> recordingFailed = true }
            native.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) completedByLimit = true }
            native.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            native.setOutputFormat(if (opus) MediaRecorder.OutputFormat.OGG else MediaRecorder.OutputFormat.MPEG_4)
            native.setAudioEncoder(if (opus) MediaRecorder.AudioEncoder.OPUS else MediaRecorder.AudioEncoder.AAC)
            native.setAudioChannels(1)
            native.setAudioSamplingRate(16000)
            native.setAudioEncodingBitRate(if (opus) 16000 else 24000)
            native.setMaxDuration(60000)
            native.setOutputFile(file.absolutePath)
            native.prepare()
            native.start()
            output = file; recorder = native; mime = if (opus) "audio/ogg" else "audio/mp4"
            started = SystemClock.elapsedRealtime()
        } catch (error: Exception) {
            runCatching { native.release() }; file.delete(); throw error
        }
    }

    @Synchronized
    fun finish(cancel: Boolean): EncodedVoice? {
        val native = recorder ?: return null
        val file = output
        val duration = seconds
        recorder = null; output = null
        try {
            val stopped = completedByLimit || runCatching { native.stop() }.isSuccess
            if (cancel || recordingFailed || duration < 0.5 || !stopped || file == null || file.length() !in 1..(2 * 1024 * 1024).toLong()) return null
            return EncodedVoice(file.readBytes(), mime)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { native.release() }; file?.delete()
            RecordingMicrophone.releasePriority(this)
        }
    }
}
