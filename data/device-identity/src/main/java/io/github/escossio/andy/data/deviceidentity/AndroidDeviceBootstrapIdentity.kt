package io.github.escossio.andy.data.deviceidentity

import io.github.escossio.andy.core.deviceidentity.DEVICE_IDENTITY_KEY_ALIAS
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapIdentity
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapPublicKeyResult
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapSignatureResult
import java.util.Base64

internal class AndroidDeviceBootstrapIdentity(
    private val crypto: AndroidKeystoreDeviceIdentityCrypto = AndroidKeystoreDeviceIdentityCrypto(),
    private val keyAlias: String = DEVICE_IDENTITY_KEY_ALIAS,
) : DeviceBootstrapIdentity {
    override fun publicKey(): DeviceBootstrapPublicKeyResult = try {
        if (!crypto.hasKey(keyAlias)) {
            DeviceBootstrapPublicKeyResult.Unavailable
        } else {
            val encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(crypto.publicKeySubjectPublicKeyInfo(keyAlias))
            if (encoded.length !in 80..2048) {
                DeviceBootstrapPublicKeyResult.Unavailable
            } else {
                DeviceBootstrapPublicKeyResult.Ready(encoded)
            }
        }
    } catch (_: Exception) {
        DeviceBootstrapPublicKeyResult.Unavailable
    }

    override fun signChallenge(challengeB64Url: String): DeviceBootstrapSignatureResult = try {
        if (!crypto.hasKey(keyAlias) ||
            challengeB64Url.length !in 32..256 ||
            !challengeB64Url.matches(Regex("^[A-Za-z0-9_-]+$"))
        ) {
            DeviceBootstrapSignatureResult.Unavailable
        } else {
            val challenge = Base64.getUrlDecoder().decode(
                challengeB64Url + "=".repeat((4 - challengeB64Url.length % 4) % 4),
            )
            val signature = crypto.signForSelfTest(keyAlias, challenge)
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
            if (encoded.length !in 64..512) {
                DeviceBootstrapSignatureResult.Unavailable
            } else {
                DeviceBootstrapSignatureResult.Signed(encoded)
            }
        }
    } catch (_: Exception) {
        DeviceBootstrapSignatureResult.Unavailable
    }
}
