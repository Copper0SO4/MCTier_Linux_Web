package top.pmh13.mctier.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object VoiceRecordingPcm {
    /** Only the bounded 16 kHz mono PCM produced by VoiceMessageRecorder is accepted. */
    fun parse(wav: ByteArray): ByteArray? {
        if (wav.size !in (44 + 16000)..(44 + 16000 * 2 * 60) || (wav.size - 44) % 2 != 0) return null
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int, value: String) = wav.copyOfRange(offset, offset + 4).contentEquals(value.toByteArray(Charsets.US_ASCII))
        if (!tag(0, "RIFF") || !tag(8, "WAVE") || !tag(12, "fmt ") || !tag(36, "data") ||
            b.getInt(4) != wav.size - 8 || b.getInt(16) != 16 || b.getShort(20).toInt() != 1 ||
            b.getShort(22).toInt() != 1 || b.getInt(24) != 16000 || b.getInt(28) != 32000 ||
            b.getShort(32).toInt() != 2 || b.getShort(34).toInt() != 16 || b.getInt(40) != wav.size - 44) return null
        return wav.copyOfRange(44, wav.size)
    }
}
