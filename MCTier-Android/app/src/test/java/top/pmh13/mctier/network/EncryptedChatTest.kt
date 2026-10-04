package top.pmh13.mctier.network

import org.junit.Assert.*
import org.junit.Test
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.math.BigInteger
import java.util.Base64

class EncryptedChatTest {
    @Test fun messagesArePairwiseAndContextBound() {
        val alice = ChatAuth.ChatSigner.generate()!!
        val bob = ChatAuth.ChatSigner.generate()!!
        val outsider = ChatAuth.ChatSigner.generate()!!
        val token = "a".repeat(64)
        val body = alice.encrypt(bob.publicKeyBase64(), token, "/api/chat/send", "private hello".toByteArray())
        assertFalse(body.toString(Charsets.UTF_8).contains("private hello"))
        assertEquals("private hello", bob.decrypt(alice.publicKeyBase64(), token, "/api/chat/send", body).toString(Charsets.UTF_8))
        assertTrue(runCatching { outsider.decrypt(alice.publicKeyBase64(), token, "/api/chat/send", body) }.isFailure)
        assertTrue(runCatching { bob.decrypt(alice.publicKeyBase64(), token, "/api/chat/messages", body) }.isFailure)
        assertTrue(runCatching { bob.decrypt(alice.publicKeyBase64(), "b".repeat(64), "/api/chat/send", body) }.isFailure)
        val envelope = org.json.JSONObject(body.toString(Charsets.UTF_8))
        val bytes = Base64.getDecoder().decode(envelope.getString("data"))
        bytes[15] = (bytes[15].toInt() xor 1).toByte()
        envelope.put("data", Base64.getEncoder().encodeToString(bytes))
        assertTrue(runCatching { bob.decrypt(alice.publicKeyBase64(), token, "/api/chat/send", envelope.toString().toByteArray()) }.isFailure)
    }

    @Test fun decryptsIndependentNodeCryptoInteropVector() {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger.valueOf(2), params))
        val public = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfPJ7GI0DT36KUjgDBLUaw8CJaeJ38hs1pgtI/EdmmXgHd1UQ247QQCk9msafdDDbun2t5jzpgimeBLedInhz0Q==")
        val constructor = ChatAuth.ChatSigner::class.java.getDeclaredConstructor(PrivateKey::class.java, ByteArray::class.java, String::class.java).apply { isAccessible = true }
        val bob = constructor.newInstance(private, public, ChatAuth.keyIdForPublicKey(public))
        val alice = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li/hp/m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q=="
        val envelope = """{"v":1,"data":"BwcHBwcHBwcHBwcHDk2BAaPbB4PVW1taZHelGpAC6wy583tf6FTKhtA="}"""
        assertEquals("private hello", bob.decrypt(alice, "a".repeat(64), "/api/chat/send", envelope.toByteArray()).toString(Charsets.UTF_8))
    }

    @Test fun encryptedInvitationsRoundTripAndRejectCorruption() {
        val invite = LobbyInviteData("Lobby123", "Secret123", "udp://example.com:11010", "wss://example.com/signaling")
        val link = LobbyInviteCodec.buildLink(invite)
        assertFalse(link.contains("Secret123"))
        assertEquals(invite, LobbyInviteCodec.parse(link))
        assertFalse(LobbyInviteCodec.formatText(invite, true).contains("Secret123"))
        assertNull(LobbyInviteCodec.parse("Lobby Name: Lobby123\nPassword: Secret123\nmctier://join?v=3&name=Lobby123&secret=invalid"))
    }
}
