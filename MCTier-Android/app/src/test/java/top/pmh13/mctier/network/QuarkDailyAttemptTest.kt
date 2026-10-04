package top.pmh13.mctier.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QuarkDailyAttemptTest {
    @Test fun loggedOutAccountDoesNotTransfer() = runBlocking {
        assertFalse(QuarkDailyAttempt.run("", "2026-10-01", null,
            { error("must not persist") }, { error("must not transfer") }))
    }
    @Test fun firstLaunchAndRepeatedChecksNeedNoManualConfirmation() = runBlocking {
        var lastDay: String? = null
        var writes = 0
        repeat(3) {
            QuarkDailyAttempt.run("alice", "2026-10-01", lastDay,
                { lastDay = "2026-10-01" }, { writes++ })
        }
        assertEquals(1, writes)
        QuarkDailyAttempt.run("alice", "2026-10-02", lastDay,
            { lastDay = "2026-10-02" }, { writes++ })
        assertEquals(2, writes)
    }
    @Test fun uncertainTransferSurvivesRestartWithoutResubmitting() = runBlocking {
        val disk = mutableMapOf<String, String>()
        var writes = 0
        try {
            QuarkDailyAttempt.run("alice", "2026-10-01", disk["alice"],
                { disk["alice"] = "2026-10-01" }, { writes++; error("response lost") })
            fail("expected lost response")
        } catch (_: IllegalStateException) { }
        val reloaded = disk.toMap()
        assertFalse(QuarkDailyAttempt.run("alice", "2026-10-01", reloaded["alice"],
            { error("must not reserve again") }, { writes++ }))
        assertEquals(1, writes)
        assertTrue(QuarkDailyAttempt.run("alice", "2026-10-02", reloaded["alice"],
            { disk["alice"] = "2026-10-02" }, { writes++ }))
        assertEquals(2, writes)
        assertTrue(QuarkDailyAttempt.due("bob", "2026-10-01", disk["bob"]))
    }
    @Test fun storageFailureCannotWriteToCloud() = runBlocking {
        var writes = 0
        try {
            QuarkDailyAttempt.run("alice", "2026-10-01", null,
                { error("disk full") }, { writes++ })
            fail("expected disk failure")
        } catch (_: IllegalStateException) { }
        assertEquals(0, writes)
    }
}
