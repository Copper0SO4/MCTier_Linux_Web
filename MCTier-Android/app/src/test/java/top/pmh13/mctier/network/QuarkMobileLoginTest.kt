package top.pmh13.mctier.network

import org.junit.Assert.*
import org.junit.Test

class QuarkMobileLoginTest {
    @Test fun onlyOfficialTicketShapeIsAccepted() {
        assertTrue(QuarkMobileLogin.validTicket("a".repeat(32)))
        for (ticket in listOf(null, "1234", "a".repeat(33), "a".repeat(31) + "\n")) {
            assertFalse(QuarkMobileLogin.validTicket(ticket))
        }
    }
    @Test fun cancelledStaleAndExpiredAttemptsCannotFinishLogin() {
        assertTrue(QuarkMobileLogin.validAttempt("new", 1_000, "new", 1_001))
        assertTrue(QuarkMobileLogin.validAttempt("new", 1_000, "new", 600_999))
        assertFalse(QuarkMobileLogin.validAttempt("new", 1_000, "old", 1_001))
        assertFalse(QuarkMobileLogin.validAttempt("new", 1_000, null, 1_001))
        assertFalse(QuarkMobileLogin.validAttempt("new", 1_000, "new", 601_000))
        assertFalse(QuarkMobileLogin.validAttempt("new", 1_000, "new", 999))
    }
}
