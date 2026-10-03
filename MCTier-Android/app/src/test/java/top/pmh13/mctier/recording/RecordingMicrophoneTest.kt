package top.pmh13.mctier.recording

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecordingMicrophoneTest {
    @Test fun callPcmIsResampledAndVoiceMessageLeaseSuppressesAndClearsIt() {
        val recording = Any(); val call = Any(); val message = Any()
        val input = ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN)
        repeat(160) { input.putShort(it * 2, 1234) }
        RecordingMicrophone.attach(recording)
        RecordingMicrophone.setCallActive(call, true)
        try {
            RecordingMicrophone.offerCall(call, input, 2, 1, 16000, 320)
            val output = ShortArray(480)
            RecordingMicrophone.read(recording, output)
            assertTrue(output.all { it == 1234.toShort() })
            RecordingMicrophone.offerCall(call, input, 2, 1, 16000, 320)
            RecordingMicrophone.acquirePriority(message)
            RecordingMicrophone.read(recording, output)
            assertTrue(output.all { it == 0.toShort() })
            RecordingMicrophone.releasePriority(message)
            RecordingMicrophone.read(recording, output)
            assertTrue(output.all { it == 0.toShort() })
            RecordingMicrophone.offerCall(call, input, 2, 1, 16000, 320)
            RecordingMicrophone.detach(Any()) // an unrelated stop cannot close this recording
            RecordingMicrophone.read(recording, output)
            assertTrue(output.all { it == 1234.toShort() })
        } finally { RecordingMicrophone.detach(recording); RecordingMicrophone.setCallActive(call, false); RecordingMicrophone.releasePriority(message) }
    }
    @Test fun backlogIsBoundedAndUnregisteredCallCannotFeedRecording() {
        val recording = Any(); val call = Any()
        val input = ByteBuffer.allocate(19200).order(ByteOrder.LITTLE_ENDIAN)
        repeat(9600) { input.putShort(it * 2, it.toShort()) }
        RecordingMicrophone.attach(recording); RecordingMicrophone.setCallActive(call, true)
        try {
            RecordingMicrophone.offerCall(Any(), input, 2, 1, 48000, 19200)
            val output = ShortArray(4800)
            RecordingMicrophone.read(recording, output)
            assertTrue(output.all { it == 0.toShort() })
            RecordingMicrophone.offerCall(call, input, 2, 1, 48000, 19200)
            RecordingMicrophone.read(recording, output)
            assertEquals(4800.toShort(), output.first()); assertEquals(9599.toShort(), output.last())
        } finally { RecordingMicrophone.detach(recording); RecordingMicrophone.setCallActive(call, false) }
    }
}
