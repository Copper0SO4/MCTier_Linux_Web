package top.pmh13.mctier.ui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig

private data class DecodedSpeechAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

internal object VoiceMessageTranscriber {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    fun transcribe(context: Context, dataUrl: String, onProgress: (String) -> Unit = {}, onResult: (Result<String>) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            onResult(Result.failure(IllegalStateException("另一条语音正在识别，请稍后重试")))
            return
        }
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val result = runCatching {
                val audio = decodeAudio(appContext, dataUrl)
                try {
                    val directory = SpeechModel.prepare(appContext) { completed, total ->
                        handler.post { onProgress(if (completed < total) L("正在初始化内置语音模型 ${completed * 100 / total}%", "Preparing bundled speech model ${completed * 100 / total}%") else L("正在识别语音…", "Transcribing voice…")) }
                    }
                    recognizePcm(directory, audio)
                } finally { audio.pcm.fill(0) }
            }
            running.set(false)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private fun decodeAudio(context: Context, dataUrl: String): DecodedSpeechAudio {
        val encoded = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
        require(encoded.isNotEmpty() && encoded.size <= 2 * 1024 * 1024)
        val source = File.createTempFile("voice-source-", ".audio", context.cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            source.writeBytes(encoded)
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputFormat = inputFormat
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!outputEnded && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: error("Missing codec input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            require(output.size() + chunk.size <= 8 * 1024 * 1024)
                            output.write(chunk)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            require(outputEnded && output.size() > 0)
            require(!outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) || outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
            return DecodedSpeechAudio(
                output.toByteArray(),
                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            encoded.fill(0)
            source.delete()
        }
    }

    private fun recognizePcm(directory: File, audio: DecodedSpeechAudio): String {
        require(audio.channels in 1..8 && audio.sampleRate in 8000..192000)
        val buffer = java.nio.ByteBuffer.wrap(audio.pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(buffer.remaining() / audio.channels) {
            var sum = 0f
            repeat(audio.channels) { sum += buffer.get() / 32768f }
            sum / audio.channels
        }
        audio.pcm.fill(0)
        require(samples.isNotEmpty() && samples.size.toLong() <= audio.sampleRate.toLong() * 120)
        val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(enableEndpoint = false, modelConfig = OnlineModelConfig(
            zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = File(directory, "model.int8.onnx").absolutePath),
            tokens = File(directory, "tokens.txt").absolutePath, numThreads = 2, provider = "cpu",
        )))
        try {
            val stream = recognizer.createStream()
            try {
                var offset = 0
                while (offset < samples.size) {
                    val end = minOf(offset + audio.sampleRate, samples.size)
                    stream.acceptWaveform(samples.copyOfRange(offset, end), audio.sampleRate)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    offset = end
                }
                // Flush the tail without resetting the accumulated transcript.
                stream.acceptWaveform(FloatArray((audio.sampleRate * .66).toInt()), audio.sampleRate)
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            }
            finally { stream.release() }
        } finally { samples.fill(0f); recognizer.release() }
    }
}
