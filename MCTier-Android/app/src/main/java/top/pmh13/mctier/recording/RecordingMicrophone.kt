package top.pmh13.mctier.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Calls own the physical microphone first. Recording taps call PCM, and yields completely
 * to chat messages/auditions. All reads are nonblocking; no capture thread waits on a codec. */
internal object RecordingMicrophone {
    private val calls = mutableSetOf<Any>()
    private val priority = mutableSetOf<Any>()
    private var subscriber: Any? = null
    private var input: AudioRecord? = null
    private val pcm = ShortArray(4800)
    private var head = 0
    private var count = 0

    @Synchronized fun attach(owner: Any) { check(subscriber == null); subscriber = owner; clear() }
    @Synchronized fun detach(owner: Any) { if (subscriber === owner) { closeInput(); subscriber = null; clear() } }
    @Synchronized fun setCallActive(owner: Any, active: Boolean) {
        if (active) { calls.add(owner); closeInput() } else calls.remove(owner)
        clear()
    }
    @Synchronized fun acquirePriority(owner: Any) { priority.add(owner); closeInput(); clear() }
    @Synchronized fun releasePriority(owner: Any) { priority.remove(owner); clear() }
    private fun clear() { head = 0; count = 0 }
    private fun closeInput() {
        val old = input; input = null
        runCatching { old?.stop() }; runCatching { old?.release() }
    }
    @Synchronized fun offerCall(owner: Any, buffer: ByteBuffer, format: Int, channels: Int, rate: Int, length: Int) {
        if (subscriber == null || owner !in calls || priority.isNotEmpty() || format != AudioFormat.ENCODING_PCM_16BIT || channels < 1 || rate <= 0) return
        val bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = minOf(length, bytes.capacity()) / (2 * channels)
        val samples = frames * 48000 / rate
        for (i in 0 until samples) {
            val frame = i * rate / 48000
            var sum = 0
            for (channel in 0 until channels) sum += bytes.getShort((frame * channels + channel) * 2).toInt()
            if (count == pcm.size) { head = (head + 1) % pcm.size; count-- }
            pcm[(head + count) % pcm.size] = (sum / channels).toShort(); count++
        }
    }
    @Suppress("MissingPermission")
    @Synchronized fun read(owner: Any, target: ShortArray): Int {
        target.fill(0)
        if (subscriber !== owner || priority.isNotEmpty()) return target.size
        if (calls.isNotEmpty()) {
            val take = minOf(count, target.size)
            repeat(take) { target[it] = pcm[head]; head = (head + 1) % pcm.size }
            count -= take
            return target.size
        }
        if (input == null) {
            val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val opened = AudioRecord(MediaRecorder.AudioSource.MIC, 48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(8192, minimum * 2))
            try { check(opened.state == AudioRecord.STATE_INITIALIZED); opened.startRecording(); input = opened }
            catch (e: Exception) { opened.release(); throw e }
        }
        val read = input!!.read(target, 0, target.size, AudioRecord.READ_NON_BLOCKING)
        check(read >= 0) { "无法读取录屏麦克风" }
        return target.size
    }
}
