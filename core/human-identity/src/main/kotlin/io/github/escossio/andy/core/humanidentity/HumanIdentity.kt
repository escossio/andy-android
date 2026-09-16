package io.github.escossio.andy.core.humanidentity

sealed interface HumanIdentityState {
    data object DeviceIdentityUnavailable : HumanIdentityState
    data object Unauthenticated : HumanIdentityState
    data object RequestingChallenge : HumanIdentityState
    data object AcquiringProviderCredential : HumanIdentityState
    data object ValidatingBackend : HumanIdentityState
    data class Validated(val humanIdentityId: String) : HumanIdentityState
    data class Failure(val reason: HumanIdentityFailure) : HumanIdentityState
}
enum class HumanIdentityFailure { CONFIGURATION_MISSING, NETWORK_FAILURE, PROVIDER_CANCELLED, PROVIDER_UNAVAILABLE, CHALLENGE_EXPIRED, CHALLENGE_CONSUMED, CREDENTIAL_REJECTED, NONCE_MISMATCH, UNEXPECTED_RESPONSE }
