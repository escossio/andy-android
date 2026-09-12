package io.github.escossio.andy.core.deviceidentity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeyFingerprintTest {
    @Test
    fun fingerprintUsesCanonicalSha256LowercaseHex() {
        val value = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(byteArrayOf(1, 2, 3, 4)).value
        assertTrue(value.matches(Regex("^sha256:[0-9a-f]{64}$")))
        assertEquals("sha256:9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", value)
    }

    @Test
    fun fingerprintIsDeterministic() {
        val bytes = byteArrayOf(9, 8, 7, 6)
        assertEquals(
            DeviceKeyFingerprint.fromSubjectPublicKeyInfo(bytes),
            DeviceKeyFingerprint.fromSubjectPublicKeyInfo(bytes),
        )
    }

    @Test
    fun parseRejectsNonCanonicalValues() {
        assertNull(DeviceKeyFingerprint.parse("SHA256:abc"))
        assertNull(DeviceKeyFingerprint.parse("sha256:ABCDEF"))
        assertNull(DeviceKeyFingerprint.parse("sha256:1234"))
    }
}
