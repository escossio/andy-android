package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class ClientSessionStoreTest {
    @Test
    fun storeBoundaryCanRoundTripCredentialWithoutExposingTokenThroughToString() = runBlocking {
        val token = "cst_" + "a".repeat(43)
        val credential = ClientSessionCredential(
            token = token,
            sessionId = "csn_" + "b".repeat(24),
            expiresAt = Instant.parse("2030-01-01T00:15:00Z"),
            humanIdentityId = "hid_" + "c".repeat(24),
            deviceId = "cdev_" + "d".repeat(24),
            tenantId = "tnt_synthetic",
        )
        val store = RecordingStore()

        store.save(credential)

        val restored = store.load()
        requireNotNull(restored)
        restored.withToken { restoredToken ->
            assertEquals(token, restoredToken)
        }
        assertFalse(restored.toString().contains(token))
    }

    private class RecordingStore : ClientSessionStore {
        private var current: ClientSessionCredential? = null

        override suspend fun load(): ClientSessionCredential? = current

        override suspend fun save(session: ClientSessionCredential) {
            current = session
        }

        override suspend fun clear() {
            current = null
        }
    }
}
