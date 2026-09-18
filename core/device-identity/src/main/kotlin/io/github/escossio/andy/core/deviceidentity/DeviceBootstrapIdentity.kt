package io.github.escossio.andy.core.deviceidentity

sealed interface DeviceBootstrapPublicKeyResult {
    data class Ready(val publicKeySpkiB64Url: String) : DeviceBootstrapPublicKeyResult
    data object Unavailable : DeviceBootstrapPublicKeyResult
}

sealed interface DeviceBootstrapSignatureResult {
    data class Signed(val signatureB64Url: String) : DeviceBootstrapSignatureResult
    data object Unavailable : DeviceBootstrapSignatureResult
}

interface DeviceBootstrapIdentity {
    fun publicKey(): DeviceBootstrapPublicKeyResult
    fun signChallenge(challengeB64Url: String): DeviceBootstrapSignatureResult
}
