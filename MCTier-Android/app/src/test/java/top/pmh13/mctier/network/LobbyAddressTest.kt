package top.pmh13.mctier.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.pmh13.mctier.data.MctierJson
import top.pmh13.mctier.data.SignalingEnvelope

class LobbyAddressTest {
    @Test fun candidatesCoverEveryUsableAddressExactlyOnce() {
        assertEquals("10.126.126.227", LobbyAddress.candidate("room", "alice", 0))
        val ips = (0..253).map { LobbyAddress.candidate("room", "alice", it) }
        assertEquals(254, ips.toSet().size)
        assertTrue(ips.contains("10.126.126.1"))
        assertTrue(ips.contains("10.126.126.254"))
        assertFalse(ips.contains("10.126.126.0"))
        assertNotEquals("10.126.126.1", ips.first())
        assertEquals(ips.first(), LobbyAddress.candidate("room", "alice", 0))
    }

    @Test fun preferredHostWinsFirstAndCollisionFallsBackToAutomaticCandidate() {
        assertEquals("10.126.126.42", LobbyAddress.candidate("room", "alice", 0, 42))
        assertEquals("10.126.126.228", LobbyAddress.candidate("room", "alice", 1, 42))
    }

    @Test fun collisionRetriesButPasswordErrorsDoNot() = runBlocking {
        val attempts = mutableListOf<Int>()
        val result = LobbyAddress.recover { attempt ->
            attempts.add(attempt)
            if (attempt < 2) throw RegistrationRejected(LobbyAddress.Conflict)
            "registered"
        }
        assertEquals("registered", result)
        assertEquals(listOf(0, 1, 2), attempts)
        var calls = 0
        try {
            LobbyAddress.recover<Unit> { calls++; throw RegistrationRejected("密码错误") }
            fail("password rejection must propagate")
        } catch (e: RegistrationRejected) { assertEquals("密码错误", e.message) }
        assertEquals(1, calls)
    }

    @Test fun cancellationAndDeadlineStopRecovery() = runBlocking {
        var calls = 0
        try {
            LobbyAddress.recover<Unit> { calls++; throw CancellationException("left") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
        var clock = 0L
        try {
            LobbyAddress.recover<Unit>(clockMillis = { clock }) {
                clock = 60_000
                throw RegistrationRejected(LobbyAddress.Conflict)
            }
            fail("deadline must stop retries")
        } catch (e: RegistrationRejected) { assertNotEquals(LobbyAddress.Conflict, e.message) }
    }

    @Test fun registrationRejectionsPreserveTheServerMessage() {
        val message = MctierJson.decodeFromString(SignalingEnvelope.serializer(),
            """{"type":"register-error","message":"virtualIp 已被大厅内其他成员使用"}""")
        assertEquals(LobbyAddress.Conflict, message.message)
    }
}
