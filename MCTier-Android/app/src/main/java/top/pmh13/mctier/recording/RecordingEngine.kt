package top.pmh13.mctier.recording

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded hardware H.264/AAC encoder. No frames or complete movie buffered in the UI. */
internal class RecordingEngine(private val context: Context, private val options: RecordingOptions, private val consent: Intent,
    private val update: (RecordingState) -> Unit) {
    private val stopped = AtomicBoolean(false)
    @Volatile private var pauseRequested = false
    // Freeze pause state for each encoding iteration: a UI resume during a blocking
    // AudioRecord.read must not submit PCM before pausedUs has been adjusted.
    private var paused = false
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var legacyFile: File? = null
    @Volatile private var playback: AudioRecord? = null
    private var muxing = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var writtenVideo = 0
    private val pending = ArrayList<Pair<Boolean, Pair<ByteArray, MediaCodec.BufferInfo>>>()
    private var pendingBytes = 0
    private var startedAt = 0L
    private var pausedUs = 0L
    private var pauseStart = 0L
    private var lastAudioPts = -1L
    private var lastVideoPts = -1L
    private var audioInputPts = -1L
    private var lastAudioSamples = 0
    private var needsKeyframe = true
    fun pause() { pauseRequested = !pauseRequested }
    fun stop() {
        stopped.set(true)
        RecordingMicrophone.detach(this); runCatching { playback?.stop() }
    }
    private fun nowUs() = System.nanoTime() / 1000
    private fun output() {
        val name = "MCTier-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())}-${java.util.UUID.randomUUID().toString().take(8)}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MCTier"); put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建录像文件")
            descriptor = context.contentResolver.openFileDescriptor(uri!!, "rw") ?: error("无法打开录像文件")
            muxer = MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            @Suppress("DEPRECATION")
            val file = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MCTier"), name)
            file.parentFile?.mkdirs(); legacyFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            uri = Uri.fromFile(file)
        }
    }
    @Suppress("MissingPermission")
    private fun audioInput(internal: Boolean): AudioRecord {
        val format = AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minBuffer = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "设备不支持录音格式" }
        val builder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
        if (internal) {
            check(Build.VERSION.SDK_INT >= 29) { "内部声音需要 Android 10 或更高版本" }
            builder.setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build())
        } else builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        return builder.build().also { check(it.state == AudioRecord.STATE_INITIALIZED) { "无法打开录音设备" }; it.startRecording() }
    }
    private fun startMuxer() {
        if (!muxing && videoTrack >= 0 && (audio == null || audioTrack >= 0)) {
            muxer!!.start(); muxing = true
            pending.forEach { (isVideo, data) -> writeSample(isVideo, data.first, data.second) }
            pending.clear(); pendingBytes = 0
        }
    }
    private fun writeSample(isVideo: Boolean, bytes: ByteArray, info: MediaCodec.BufferInfo) {
        muxer!!.writeSampleData(if (isVideo) videoTrack else audioTrack, ByteBuffer.wrap(bytes), info)
        if (isVideo) writtenVideo++
    }
    private fun drain(codec: MediaCodec, isVideo: Boolean, ending: Boolean = false): Boolean {
        val info = MediaCodec.BufferInfo()
        repeat(64) {
            when (val index = codec.dequeueOutputBuffer(info, if (ending) 10000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val track = muxer!!.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    startMuxer()
                }
                else -> if (index >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && !paused) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            val pts = if (isVideo) maxOf(0, info.presentationTimeUs - startedAt - pausedUs) else info.presentationTimeUs
                            val previous = if (isVideo) lastVideoPts else lastAudioPts
                            val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            if (pts > previous && (!isVideo || !needsKeyframe || keyframe)) {
                                if (isVideo && keyframe) needsKeyframe = false
                                if (isVideo) lastVideoPts = pts else lastAudioPts = pts
                                val copy = MediaCodec.BufferInfo().apply { set(0, bytes.size, pts, info.flags) }
                                if (muxing) writeSample(isVideo, bytes, copy) else {
                                    pendingBytes += bytes.size
                                    check(pendingBytes <= 16 * 1024 * 1024) { "编码器初始化超时" }
                                    pending += isVideo to (bytes to copy)
                                }
                            }
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (eos) return true
                }
            }
        }
        return false
    }
    fun run(): RecordingState {
        var error = ""
        var seconds = 0L
        var complete = false
        try {
            options.validate()
            check(!stopped.get()) { "录制已取消" }
            output()
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(Activity.RESULT_OK, consent)
            projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stop() } }, Handler(Looper.getMainLooper()))
            val metrics = context.resources.displayMetrics
            val (width, height) = options.size(metrics.widthPixels, metrics.heightPixels)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, options.bitrate * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, options.fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / options.fps)
                setFloat("max-fps-to-encoder", options.fps.toFloat())
            }
            video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            video!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = video!!.createInputSurface(); video!!.start()
            if (options.microphone || options.systemAudio) {
                audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audio!!.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audio!!.start()
                if (options.microphone) RecordingMicrophone.attach(this)
                if (options.systemAudio) playback = audioInput(true)
            }
            startedAt = nowUs()
            display = projection!!.createVirtualDisplay("MCTier recording", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, Handler(Looper.getMainLooper()))
            val mic = ShortArray(960); val internal = ShortArray(960)
            var nextAudioAt = nowUs()
            var lastUpdate = 0L
            while (!stopped.get()) {
                paused = pauseRequested
                if (paused && pauseStart == 0L) pauseStart = nowUs()
                if (!paused && pauseStart != 0L) {
                    pausedUs += nowUs() - pauseStart; pauseStart = 0L
                    audioInputPts = -1L
                    needsKeyframe = true
                    video!!.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
                drain(video!!, true)
                if (audio != null) {
                    val wait = nextAudioAt - nowUs()
                    if (wait > 0) Thread.sleep(wait / 1000, ((wait % 1000) * 1000).toInt())
                    nextAudioAt = maxOf(nextAudioAt + 20000, nowUs())
                    val a = if (options.microphone) RecordingMicrophone.read(this, mic) else mic.size
                    val b = playback?.read(internal, 0, internal.size, AudioRecord.READ_BLOCKING) ?: internal.size
                    if (stopped.get()) break
                    check(a > 0 && b > 0) { "录音设备已停止或被其他应用占用" }
                    if (!paused) {
                        val mixed = if (options.microphone) mixRecordingPcm(mic, if (options.systemAudio) internal else null, minOf(a, b)) else internal.copyOf(b)
                        val input = audio!!.dequeueInputBuffer(10000)
                        if (input >= 0) {
                            val buffer = audio!!.getInputBuffer(input)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            buffer.clear(); mixed.forEach { buffer.putShort(it) }
                            val wall = maxOf(0, nowUs() - startedAt - pausedUs - mixed.size * 1_000_000L / 48000)
                            audioInputPts = if (audioInputPts < 0 || wall - audioInputPts > 200000) maxOf(lastAudioPts + 1, wall)
                                else audioInputPts + lastAudioSamples * 1_000_000L / 48000
                            lastAudioSamples = mixed.size
                            audio!!.queueInputBuffer(input, 0, mixed.size * 2, audioInputPts, 0)
                        }
                    }
                    drain(audio!!, false)
                } else Thread.sleep(5)
                val elapsed = nowUs() - startedAt - pausedUs - (if (pauseStart != 0L) nowUs() - pauseStart else 0L)
                seconds = elapsed / 1_000_000
                if (nowUs() - lastUpdate > 500000) {
                    update(RecordingState(if (paused) "paused" else "recording", seconds))
                    check(StatFs(context.getExternalFilesDir(null)!!.absolutePath).availableBytes > 64L * 1024 * 1024) { "存储空间不足，录制已停止" }
                    lastUpdate = nowUs()
                }
            }
            update(RecordingState("saving", seconds))
            video!!.signalEndOfInputStream()
            audio?.let { encoder ->
                val index = encoder.dequeueInputBuffer(100000)
                if (index >= 0) encoder.queueInputBuffer(index, 0, 0, maxOf(0, audioInputPts + lastAudioSamples * 1_000_000L / 48000), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val deadline = nowUs() + 3_000_000
            var videoDone = false; var audioDone = audio == null
            while ((!videoDone || !audioDone) && nowUs() < deadline) {
                if (!videoDone) videoDone = drain(video!!, true, true)
                if (!audioDone) audioDone = drain(audio!!, false, true)
            }
            check(muxing && writtenVideo > 0) { "未收到可保存的视频帧，请延长录制时间或降低画质" }
            muxer!!.stop(); muxing = false; complete = true
        } catch (e: Exception) {
            error = e.message ?: "录屏失败"
            // Preserve a finalized playable prefix after device loss or low storage.
            if (muxing && writtenVideo > 0) complete = runCatching { muxer!!.stop(); muxing = false }.isSuccess
        } finally {
            stopped.set(true)
            RecordingMicrophone.detach(this)
            runCatching { playback?.stop() }; runCatching { playback?.release() }; playback = null
            runCatching { display?.release() }; runCatching { projection?.stop() }
            runCatching { video?.stop() }; runCatching { video?.release() }; runCatching { surface?.release() }
            runCatching { audio?.stop() }; runCatching { audio?.release() }
            runCatching { muxer?.release() }; runCatching { descriptor?.close() }
            if (complete && legacyFile != null) {
                // Android 8/9 require public Movies plus media scanning to appear in Gallery.
                runCatching { MediaScannerConnection.scanFile(context, arrayOf(legacyFile!!.absolutePath), arrayOf("video/mp4"), null) }
                    .onFailure { error = "录像已保存，但相册索引失败: ${it.message}" }
            }
            uri?.let { target ->
                if (complete && Build.VERSION.SDK_INT >= 29) runCatching {
                    context.contentResolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                }.onFailure { error = "录像已写入，但无法发布到相册: ${it.message}"; complete = false }
                else if (!complete) runCatching { if (legacyFile != null) legacyFile!!.delete() else context.contentResolver.delete(target, null, null) }
            }
        }
        return RecordingState("idle", seconds, if (complete) uri.toString() else "", error)
    }
}
