package io.github.escossio.andy.core.humanidentity

/** In-memory state machine; only backendValidated can produce Validated. */
class HumanIdentityEngine(private val deviceReady: Boolean) {
    var state: HumanIdentityState = if (deviceReady) HumanIdentityState.Unauthenticated else HumanIdentityState.DeviceIdentityUnavailable
        private set
    fun begin() = set(if (deviceReady) HumanIdentityState.RequestingChallenge else HumanIdentityState.DeviceIdentityUnavailable)
    fun challengeReceived() = transition(HumanIdentityState.RequestingChallenge, HumanIdentityState.AcquiringProviderCredential)
    fun providerCredentialReceived() = transition(HumanIdentityState.AcquiringProviderCredential, HumanIdentityState.ValidatingBackend)
    fun backendValidated(id: String) = set(if (state == HumanIdentityState.ValidatingBackend && id.isNotBlank()) HumanIdentityState.Validated(id) else HumanIdentityState.Failure(HumanIdentityFailure.UNEXPECTED_RESPONSE))
    fun fail(reason: HumanIdentityFailure) = set(HumanIdentityState.Failure(reason))
    fun retry() = set(if (deviceReady) HumanIdentityState.Unauthenticated else HumanIdentityState.DeviceIdentityUnavailable)
    private fun transition(expected: HumanIdentityState, next: HumanIdentityState) = set(if (state == expected) next else HumanIdentityState.Failure(HumanIdentityFailure.UNEXPECTED_RESPONSE))
    private fun set(next: HumanIdentityState): HumanIdentityState { state = next; return next }
}
