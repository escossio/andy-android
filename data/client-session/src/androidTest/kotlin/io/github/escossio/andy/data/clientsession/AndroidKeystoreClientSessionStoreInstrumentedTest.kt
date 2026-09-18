package io.github.escossio.andy.data.clientsession

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreClientSessionStoreInstrumentedTest {
    @Test
    fun androidKeystoreEncryptsSessionAndTamperingFailsClosed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val fileName = "client-session-test-$suffix.json"
        val alias = "andy_client_session_test_$suffix"
        val file = File(context.noBackupFilesDir, fileName)
        val store = AndroidKeystoreClientSessionStore(context, fileName, alias)
        val token = "cst_" + "a".repeat(43)
        val credential = ClientSessionCredential(
            token = token,
            sessionId = "csn_" + "b".repeat(24),
            expiresAt = Instant.parse("2030-01-01T00:15:00Z"),
            humanIdentityId = "hid_" + "c".repeat(24),
            deviceId = "cdev_" + "d".repeat(24),
            tenantId = "tnt_synthetic",
        )

        try {
            store.save(credential)

            assertTrue(file.exists())
            val persisted = file.readText()
            assertFalse(persisted.contains(token))
            assertFalse(persisted.contains("session_token"))
            assertFalse(persisted.contains(credential.humanIdentityId))

            val restored = store.load()
            requireNotNull(restored)
            restored.withToken { restoredToken ->
                assertEquals(token, restoredToken)
            }
            assertEquals(credential.sessionId, restored.sessionId)
            assertEquals(credential.deviceId, restored.deviceId)
            assertEquals(credential.tenantId, restored.tenantId)

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey(alias, null)
            requireNotNull(key)
            assertNull(key.encoded)

            val tampered = file.readBytes()
            tampered[tampered.lastIndex] = (tampered.last() xor 1)
            file.writeBytes(tampered)

            assertNull(store.load())
            assertFalse(file.exists())
        } finally {
            store.clear()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }

    private infix fun Byte.xor(value: Int): Byte = (toInt() xor value).toByte()
}
