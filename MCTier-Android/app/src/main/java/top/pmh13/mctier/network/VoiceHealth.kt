package top.pmh13.mctier.network

import org.json.JSONObject

internal fun shouldQueueVoiceIce(remoteDescriptionSet: Boolean, add: () -> Boolean): Boolean =
    !remoteDescriptionSet || !runCatching(add).getOrDefault(false)

internal fun parseVoiceHealth(data: String): Long? {
    if (data.length > 128) return null
    return runCatching {
        val value = JSONObject(data)
        val packets = value.opt("packets") as? Number ?: return null
        val count = packets.toDouble()
        if (value.opt("v") != 1 || !count.isFinite() || count < 0 || count > 9007199254740991.0 || count != packets.toLong().toDouble()) null else packets.toLong()
    }.getOrNull()
}

/** Silence/DTX is healthy. A stalled receiver needs evidence of ongoing remote RTP. */
internal class VoiceHealth {
    private var previousReceived: Long? = null
    private var previousSent: Long? = null
    private var suspectSince: Long? = null
    private var remoteProgressAt: Long? = null
    private var lastReason: String? = null
    private var nextRepairAt = 0L
    private var failures = 0
    private var healthySince: Long? = null

    fun resetSample() {
        previousReceived = null; previousSent = null; suspectSince = null
        remoteProgressAt = null; lastReason = null; healthySince = null
    }

    fun observe(now: Long, received: Long, sent: Long?, broken: Boolean): String? {
        val previous = previousReceived
        val oldSent = previousSent
        previousReceived = received; previousSent = sent
        if ((previous != null && received < previous) || (sent != null && oldSent != null && sent < oldSent)) {
            resetSample(); previousReceived = received; previousSent = sent
            return null
        }
        if (sent != null && oldSent != null && sent > oldSent) remoteProgressAt = now
        val receiving = previous != null && received > previous
        val reason = when {
            broken -> "audio-track-or-negotiation"
            previous != null && !receiving && sent != null && remoteProgressAt?.let { now - it < 6000 } == true -> "inbound-audio-stalled"
            else -> null
        }
        if (reason == null) {
            suspectSince = null; lastReason = null
            if (receiving) {
                if (healthySince == null) healthySince = now
                if (now - healthySince!! >= 60000) { failures = 0; nextRepairAt = 0L }
            } else healthySince = null
            return null
        }
        healthySince = null
        if (reason != lastReason) { suspectSince = now; lastReason = reason }
        if (now - (suspectSince ?: now) < 8000 || now < nextRepairAt) return null
        nextRepairAt = now + minOf(120000L, 30000L * (1L shl failures.coerceAtMost(2)))
        failures = (failures + 1).coerceAtMost(3)
        suspectSince = now
        return reason
    }
}
