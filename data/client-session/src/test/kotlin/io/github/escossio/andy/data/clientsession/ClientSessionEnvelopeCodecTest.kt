package io.github.escossio.andy.data.clientsession

import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClientSessionEnvelopeCodecTest {
    @Test
    fun encryptedEnvelopeRoundTripsWithoutEmbeddingRawCredential() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(96) { (it + 20).toByte() }
        val encoded = ClientSessionEnvelopeCodec.encodeEnvelope(iv, ciphertext)

        val decoded = ClientSessionEnvelopeCodec.decodeEnvelope(encoded)

        requireNotNull(decoded)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
        assertFalse(encoded.toString(Charsets.UTF_8).contains("cst_"))
    }

    @Test
    fun credentialPayloadRoundTripsStrictAuthorityMetadata() = runBlocking {
        val credential = credential()
        val encoded = ClientSessionEnvelopeCodec.encodeCredential(credential)

        val decoded = ClientSessionEnvelopeCodec.decodeCredential(encoded)

        requireNotNull(decoded)
        assertEquals(credential.sessionId, decoded.sessionId)
        assertEquals(credential.expiresAt, decoded.expiresAt)
        assertEquals(credential.humanIdentityId, decoded.humanIdentityId)
        assertEquals(credential.deviceId, decoded.deviceId)
        assertEquals(credential.tenantId, decoded.tenantId)
        decoded.withToken { assertEquals(TOKEN, it) }
        encoded.fill(0)
    }

    @Test
    fun malformedOrUnexpectedEnvelopeFailsClosed() {
        assertNull(ClientSessionEnvelopeCodec.decodeEnvelope(byteArrayOf()))
        assertNull(
            ClientSessionEnvelopeCodec.decodeEnvelope(
                """{"schema_version":2,"iv_b64url":"AA","ciphertext_b64url":"AA"}"""
                    .toByteArray(),
            ),
        )
        assertNull(
            ClientSessionEnvelopeCodec.decodeEnvelope(
                """{"schema_version":1,"iv_b64url":"AA","ciphertext_b64url":"AA","extra":"x"}"""
                    .toByteArray(),
            ),
        )
    }

    @Test
    fun encodedEnvelopeContainsOnlyVersionIvAndCiphertext() {
        val encoded = ClientSessionEnvelopeCodec.encodeEnvelope(
            ByteArray(12) { 1 },
            ByteArray(64) { 2 },
        ).toString(Charsets.UTF_8)

        assertTrue(encoded.contains("schema_version"))
        assertTrue(encoded.contains("iv_b64url"))
        assertTrue(encoded.contains("ciphertext_b64url"))
        assertFalse(encoded.contains("session_token"))
        assertFalse(encoded.contains("human_identity_id"))
    }

    private fun credential() = ClientSessionCredential(
        token = TOKEN,
        sessionId = "csn_" + "b".repeat(24),
        expiresAt = Instant.parse("2030-01-01T00:15:00Z"),
        humanIdentityId = "hid_" + "c".repeat(24),
        deviceId = "cdev_" + "d".repeat(24),
        tenantId = "tnt_synthetic",
    )

    private companion object {
        const val TOKEN = "cst_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
