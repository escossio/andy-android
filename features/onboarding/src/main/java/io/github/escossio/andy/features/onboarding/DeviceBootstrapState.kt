package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapEstablished

sealed interface DeviceBootstrapState {
    data object Idle : DeviceBootstrapState
    data object Establishing : DeviceBootstrapState
    data class Established(val authority: DeviceBootstrapEstablished) : DeviceBootstrapState
    data class Failure(val reason: DeviceBootstrapFailure) : DeviceBootstrapState
}

enum class DeviceBootstrapFailure {
    DEVICE_IDENTITY_UNAVAILABLE,
    NETWORK_FAILURE,
    DEVICE_BOOTSTRAP_GRANT_REJECTED,
    DEVICE_BOOTSTRAP_CHALLENGE_NOT_FOUND,
    DEVICE_BOOTSTRAP_CHALLENGE_EXPIRED,
    DEVICE_BOOTSTRAP_CHALLENGE_CONSUMED,
    DEVICE_BOOTSTRAP_DEVICE_KEY_INVALID,
    DEVICE_BOOTSTRAP_SIGNATURE_INVALID,
    DEVICE_BOOTSTRAP_MEMBERSHIP_CONFLICT,
    DEVICE_BOOTSTRAP_DEVICE_CONFLICT,
    DEVICE_BOOTSTRAP_UNAVAILABLE,
    HUMAN_IDENTITY_MISMATCH,
    UNEXPECTED_RESPONSE,
}
