package top.pmh13.mctier.network

import org.junit.Assert.*
import org.junit.Test

class VoiceHealthTest {
    @Test fun healthyReceptionClearsBackoff() {
        val health = VoiceHealth()
        for (t in 0L..100000L step 2000L) health.observe(t, 0L, null, true)
        for (t in 102000L..164000L step 2000L) assertNull(health.observe(t, t, t, false))
        for (t in 166000L until 174000L step 2000L) assertNull(health.observe(t, 164000L, null, true))
        assertEquals("audio-track-or-negotiation", health.observe(174000L, 164000L, null, true))
    }
    @Test fun healthySilenceDtxAndMissingTelemetryDoNotRepair() {
        for (mode in listOf("flowing", "silent", "missing", "stale")) {
            val health = VoiceHealth()
            for (t in 0L until 180000L step 2000L) {
                val sent = when (mode) { "missing" -> null; "silent" -> 0L; "stale" -> minOf(t, 4000L); else -> t }
                assertNull("$mode at $t", health.observe(t, if (mode == "flowing") t else 0L, sent, false))
            }
        }
    }
    @Test fun stalledInboundRequiresSustainedRemoteProgress() {
        val health = VoiceHealth()
        for (t in 0L until 10000L step 2000L) assertNull(health.observe(t, 0L, t, false))
        assertEquals("inbound-audio-stalled", health.observe(10000L, 0L, 10000L, false))
    }
    @Test fun transientLossDoesNotRebuild() {
        val health = VoiceHealth()
        for (t in 0L until 180000L step 2000L) assertNull(health.observe(t, t / 6000L, t, false))
    }
    @Test fun brokenTrackAndNegotiationBackOffAcrossReplacements() {
        val health = VoiceHealth()
        for (t in 0L until 8000L step 2000L) assertNull(health.observe(t, 0L, null, true))
        assertEquals("audio-track-or-negotiation", health.observe(8000L, 0L, null, true))
        health.resetSample()
        for (t in 10000L until 38000L step 2000L) assertNull(health.observe(t, 0L, null, true))
        assertEquals("audio-track-or-negotiation", health.observe(38000L, 0L, null, true))
        for (t in 40000L until 98000L step 2000L) assertNull(health.observe(t, 0L, null, true))
        assertEquals("audio-track-or-negotiation", health.observe(98000L, 0L, null, true))
    }
    @Test fun countersCanResetOrDisappear() {
        val health = VoiceHealth()
        assertNull(health.observe(0, 100, 100, false))
        assertNull(health.observe(2000, 100, 200, false))
        assertNull(health.observe(4000, 0, 0, false))
        assertNull(health.observe(6000, 0, null, false))
        assertNull(health.observe(30000, 0, null, false))
    }
    @Test fun boundedHealthProtocolMatchesDesktop() {
        assertEquals(123L, parseVoiceHealth("{\"v\":1,\"packets\":123}"))
        assertEquals(0L, parseVoiceHealth("{\"v\":1,\"packets\":0}"))
        for (value in listOf("", "null", "{", " ".repeat(129), "{\"v\":2,\"packets\":1}", "{\"v\":1,\"packets\":-1}", "{\"v\":1,\"packets\":\"1\"}", "{\"v\":1,\"packets\":0.5}", "{\"v\":1,\"packets\":9007199254740992}")) assertNull(parseVoiceHealth(value))
    }
    @Test fun earlyIceFalseAndExceptionsAreQueued() {
        assertTrue(shouldQueueVoiceIce(false) { error("must not submit before SDP") })
        assertTrue(shouldQueueVoiceIce(true) { false })
        assertTrue(shouldQueueVoiceIce(true) { error("not ready") })
        assertFalse(shouldQueueVoiceIce(true) { true })
    }
}
