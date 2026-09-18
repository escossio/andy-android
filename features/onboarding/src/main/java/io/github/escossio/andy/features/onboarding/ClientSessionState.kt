package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.sdk.clientapi.AuthenticatedClientBootstrap

sealed interface ClientSessionState {
    data object Idle : ClientSessionState
    data object Restoring : ClientSessionState
    data object Establishing : ClientSessionState
    data object LoadingBootstrap : ClientSessionState
    data class Connected(val bootstrap: AuthenticatedClientBootstrap) : ClientSessionState
    data class Failure(val reason: ClientSessionFailure) : ClientSessionState
}

enum class ClientSessionFailure {
    DEVICE_IDENTITY_UNAVAILABLE,
    SESSION_STORAGE_UNAVAILABLE,
    NETWORK_FAILURE,
    CLIENT_SESSION_DEVICE_REJECTED,
    CLIENT_SESSION_CHALLENGE_NOT_FOUND,
    CLIENT_SESSION_CHALLENGE_EXPIRED,
    CLIENT_SESSION_CHALLENGE_CONSUMED,
    CLIENT_SESSION_SIGNATURE_INVALID,
    CLIENT_SESSION_ACTIVE_TENANT_REQUIRED,
    CLIENT_SESSION_TENANT_FORBIDDEN,
    CLIENT_SESSION_UNAUTHENTICATED,
    CLIENT_SESSION_AUTHORITY_REJECTED,
    CLIENT_SESSION_UNAVAILABLE,
    SESSION_BOOTSTRAP_MISMATCH,
    UNEXPECTED_RESPONSE,
}
