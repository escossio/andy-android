package io.github.escossio.andy.core.humanidentity

data class HumanIdentityReference(val opaqueId: String)

sealed interface HumanIdentityState {
    data object DeviceIdentityUnavailable : HumanIdentityState
    data object Unauthenticated : HumanIdentityState
    data object RequestingChallenge : HumanIdentityState
    data object AcquiringProviderCredential : HumanIdentityState
    data object ValidatingBackend : HumanIdentityState
    data class Validated(val identity: HumanIdentityReference) : HumanIdentityState
    data class Failure(val reason: HumanIdentityFailure) : HumanIdentityState
}
enum class HumanIdentityFailure { CONFIGURATION_MISSING, NETWORK_FAILURE, PROVIDER_CANCELLED, PROVIDER_UNAVAILABLE, HUMAN_AUTH_DISABLED, HUMAN_AUTH_CHALLENGE_NOT_FOUND, CHALLENGE_EXPIRED, CHALLENGE_CONSUMED, CREDENTIAL_REJECTED, NONCE_MISMATCH, UNEXPECTED_RESPONSE }
