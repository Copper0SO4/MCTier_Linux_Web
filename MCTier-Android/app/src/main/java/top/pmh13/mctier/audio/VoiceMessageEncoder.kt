package top.pmh13.mctier.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import java.io.File

data class EncodedVoice(val bytes: ByteArray, val mime: String)

/** Encode once after recording, on the IO dispatcher; never recompress received messages. */
object VoiceMessageEncoder {
    fun compress(context: Context, wav: ByteArray): EncodedVoice {
        val original = EncodedVoice(wav, "audio/wav")
        val pcm = VoiceRecordingPcm.parse(wav) ?: return original
        // Opus is available from Android 10; AAC-LC covers older devices and codec failures.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { encode(context, pcm, "audio/opus", 16000, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, "audio/ogg") }
                .getOrNull()?.takeIf { it.bytes.size < wav.size }?.let { return it }
        }
        return runCatching { encode(context, pcm, "audio/mp4a-latm", 24000, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "audio/mp4") }
            .getOrNull()?.takeIf { it.bytes.size < wav.size } ?: original
    }

    private fun encode(context: Context, pcm: ByteArray, codecMime: String, bitrate: Int, container: Int, mime: String): EncodedVoice {
        val file = File.createTempFile("voice-encode-", ".tmp", context.cacheDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        try {
            val encoder = MediaCodec.createEncoderByType(codecMime).also { codec = it }
            val format = MediaFormat.createAudioFormat(codecMime, 16000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                if (codecMime == "audio/mp4a-latm") setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val output = MediaMuxer(file.absolutePath, container).also { muxer = it }
            encoder.start(); codecStarted = true
            var offset = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            var encodedBytes = 0
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Voice encoder timed out" }
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = checkNotNull(encoder.getInputBuffer(index))
                        input.clear()
                        val count = minOf(input.remaining(), pcm.size - offset).let { it - it % 2 }
                        check(count > 0 || offset == pcm.size)
                        if (count > 0) input.put(pcm, offset, count)
                        val pts = offset.toLong() * 1_000_000 / 32000
                        inputEnded = offset == pcm.size
                        encoder.queueInputBuffer(index, 0, count, pts, if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        offset += count
                    }
                }
                when (val index = encoder.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted)
                        track = output.addTrack(encoder.outputFormat)
                        output.start(); muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted)
                                val data = checkNotNull(encoder.getOutputBuffer(index))
                                data.position(info.offset); data.limit(info.offset + info.size)
                                output.writeSampleData(track, data, info)
                                encodedBytes += info.size
                                check(encodedBytes <= 2 * 1024 * 1024)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            check(muxerStarted && encodedBytes > 0)
            output.stop(); muxerStarted = false
            check(file.length() in 1..(2 * 1024 * 1024).toLong())
            return EncodedVoice(file.readBytes(), mime)
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            file.delete()
        }
    }
}
