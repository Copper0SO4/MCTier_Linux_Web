package top.pmh13.mctier.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceRecordingPcmTest {
    private fun recording(samples: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(b.capacity() - 8).put("WAVEfmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { b.putShort((it % 30000).toShort()) }
        return b.array()
    }

    @Test fun preservesPcmAtMinimumAndMaximumRecordingLengths() {
        for (samples in listOf(8000, 16000 * 60)) {
            val wav = recording(samples)
            assertArrayEquals(wav.copyOfRange(44, wav.size), VoiceRecordingPcm.parse(wav))
        }
    }

    @Test fun rejectsUnsupportedAndCorruptInputBeforeInvokingDeviceCodecs() {
        assertNull(VoiceRecordingPcm.parse(byteArrayOf()))
        assertNull(VoiceRecordingPcm.parse(recording(7999)))
        assertNull(VoiceRecordingPcm.parse(recording(16000 * 60 + 1)))
        val original = recording(16000)
        for (offset in listOf(0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40)) {
            val invalid = original.copyOf(); invalid[offset] = (invalid[offset].toInt() xor 1).toByte()
            assertNull("Invalid header at $offset", VoiceRecordingPcm.parse(invalid))
        }
        assertNull(VoiceRecordingPcm.parse(original.copyOf(original.size - 1)))
    }
}
