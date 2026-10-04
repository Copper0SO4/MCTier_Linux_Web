package top.pmh13.mctier.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupVersionStageTest {
    @Test fun serverRejectionAlwaysPreemptsLowerPrompts() {
        for (checked in listOf(false, true)) for (optional in listOf(false, true)) {
            assertEquals(StartupVersionStage.Mandatory, startupVersionStage(true, checked, optional))
        }
    }

    @Test fun slowCheckDoesNotReleaseSponsorship() {
        assertEquals(StartupVersionStage.Checking, startupVersionStage(false, false, false))
        assertEquals(StartupVersionStage.Checking, startupVersionStage(false, false, true))
    }

    @Test fun skippingOptionalOrFinishingWithoutUpdateReleasesSponsorship() {
        assertEquals(StartupVersionStage.Optional, startupVersionStage(false, true, true))
        assertEquals(StartupVersionStage.Ready, startupVersionStage(false, true, false))
        // An interrupt does not consume the optional update; it resumes after leaving the rejected connection.
        assertEquals(StartupVersionStage.Mandatory, startupVersionStage(true, true, true))
        assertEquals(StartupVersionStage.Optional, startupVersionStage(false, true, true))
    }
}
