package io.github.escossio.andy.data.deviceidentity

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapPublicKeyResult
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapSignatureResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDeviceBootstrapIdentityInstrumentationTest {
    @Test
    fun establishedDeviceKeyCanExposePublicSpkiAndSignWithoutExportingPrivateKey() {
        val identity = AndroidDeviceBootstrapIdentity()
        val publicKey = identity.publicKey()
        assertTrue(publicKey is DeviceBootstrapPublicKeyResult.Ready)
        val encoded = (publicKey as DeviceBootstrapPublicKeyResult.Ready).publicKeySpkiB64Url
        assertTrue(encoded.length in 80..2048)

        val signature = identity.signChallenge("Y2hhbGxlbmdlLXN5bnRoZXRpYy0wMTIzNDU2Nzg5")
        assertTrue(signature is DeviceBootstrapSignatureResult.Signed)
        val value = (signature as DeviceBootstrapSignatureResult.Signed).signatureB64Url
        assertTrue(value.length in 64..512)
        assertFalse(value.contains("PRIVATE"))
    }
}
