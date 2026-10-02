package top.pmh13.mctier.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuarkContributionLedgerTest {
    @Test fun onlyDistinctSuccessfulDatesCountNotElapsedDaysOrLogin() {
        val saved = JSONObject()
        assertEquals(0, QuarkContributionLedger.summary(saved, "alice", "2026-10-01").successDays)
        QuarkContributionLedger.recordSuccess(saved, "alice", "2026-10-01")
        QuarkContributionLedger.recordSuccess(saved, "alice", "2026-10-03")
        QuarkContributionLedger.recordSuccess(saved, "alice", "2026-10-01")
        val stats = QuarkContributionLedger.summary(saved, "alice", "2026-10-03")
        assertEquals(2, stats.successDays)
        assertEquals(44L, stats.pcReferenceCents)
        assertEquals(94L, stats.mobileReferenceCents)
        assertEquals("2026-10-01", stats.firstDay)
        assertEquals("2026-10-03", stats.lastDay)
    }

    @Test fun accountSwitchRestartAndLogoutDoNotMixRecordsOrRepriceHistory() {
        val saved = JSONObject()
        QuarkContributionLedger.recordSuccess(saved, "alice", "2026-10-01")
        saved.getJSONObject("contributions").getJSONObject("alice").getJSONObject("2026-10-01").put("pcCents", 30)
        QuarkContributionLedger.recordSuccess(saved, "alice", "2026-10-01")
        QuarkContributionLedger.recordSuccess(saved, "bob", "2026-10-01")
        val reloaded = JSONObject(saved.toString())
        assertEquals(0, QuarkContributionLedger.summary(reloaded, "", "2026-10-01").successDays)
        assertEquals(30L, QuarkContributionLedger.summary(reloaded, "alice", "2026-10-01").pcReferenceCents)
        assertEquals(22L, QuarkContributionLedger.summary(reloaded, "bob", "2026-10-01").pcReferenceCents)
    }

    @Test fun oldAttemptsAndUnconfirmedResultsCannotBeCountedAsIncome() {
        val saved = JSONObject().put("attempts", JSONObject().put("alice", "2026-10-01"))
            .put("result", "2026-10-01：转存成功（不代表计佣成功）")
        val stats = QuarkContributionLedger.summary(saved, "alice", "2026-10-01")
        assertTrue(stats.todayAttempted)
        assertEquals(0, stats.successDays)
        assertEquals(0L, stats.mobileReferenceCents)
        assertFalse(QuarkContributionLedger.summary(saved, "alice", "2026-10-02").todayAttempted)
    }

    @Test fun malformedRecordsAreNotGivenDefaultRewards() {
        val saved = JSONObject().put("contributions", JSONObject().put("alice", JSONObject()
            .put("2026-10-01", JSONObject().put("pcCents", -1))
            .put("not-a-date", JSONObject().put("pcCents", 22).put("mobileCents", 47))))
        assertEquals(0, QuarkContributionLedger.summary(saved, "alice", "2026-10-01").successDays)
    }
}
