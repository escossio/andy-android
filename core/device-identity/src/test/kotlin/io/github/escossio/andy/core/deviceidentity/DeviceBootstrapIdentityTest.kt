package io.github.escossio.andy.core.deviceidentity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DeviceBootstrapIdentityTest {
    @Test
    fun publicMaterialAndSignatureAreExplicitResults() {
        val identity = object : DeviceBootstrapIdentity {
            override fun publicKey() =
                DeviceBootstrapPublicKeyResult.Ready("A".repeat(120))

            override fun signChallenge(challengeB64Url: String) =
                DeviceBootstrapSignatureResult.Signed("B".repeat(96))
        }

        assertEquals(
            "A".repeat(120),
            (identity.publicKey() as DeviceBootstrapPublicKeyResult.Ready).publicKeySpkiB64Url,
        )
        assertEquals(
            "B".repeat(96),
            (identity.signChallenge("C".repeat(43)) as DeviceBootstrapSignatureResult.Signed)
                .signatureB64Url,
        )
    }

    @Test
    fun unavailableResultCarriesNoCredentialMaterial() {
        assertSame(
            DeviceBootstrapPublicKeyResult.Unavailable,
            DeviceBootstrapPublicKeyResult.Unavailable,
        )
        assertSame(
            DeviceBootstrapSignatureResult.Unavailable,
            DeviceBootstrapSignatureResult.Unavailable,
        )
    }
}
