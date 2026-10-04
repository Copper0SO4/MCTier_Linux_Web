package top.pmh13.mctier.data

import org.junit.Assert.*
import org.junit.Test

class LobbyModerationTest {
    @Test fun mutedMemberBecomesHostAndDoesNotRegainOldMuteAfterTransfer() {
        val muted = setOf("phone", "other")
        assertTrue(isMutedByLobbyHost("phone", "desktop", muted))
        val promoted = lobbyMutesWithoutHost(muted, "phone")
        assertEquals(setOf("other"), promoted)
        assertFalse(isMutedByLobbyHost("phone", "phone", promoted))
        assertFalse(isMutedByLobbyHost("phone", "desktop", promoted))
        assertTrue(isMutedByLobbyHost("phone", "desktop", promoted + "phone"))
    }

    @Test fun oldServerSnapshotNeverRestrictsTheCurrentHost() {
        assertFalse(isMutedByLobbyHost("phone", "phone", setOf("phone")))
        assertEquals(emptySet<String>(), lobbyMutesWithoutHost(setOf("phone"), "phone"))
        assertEquals(setOf("phone"), lobbyMutesWithoutHost(setOf("phone"), null))
    }
}
