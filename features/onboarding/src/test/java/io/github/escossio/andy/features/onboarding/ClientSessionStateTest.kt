package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.sdk.clientapi.AuthenticatedClientBootstrap
import io.github.escossio.andy.sdk.clientapi.ClientDevice
import io.github.escossio.andy.sdk.clientapi.ClientTenantMembership
import io.github.escossio.andy.sdk.clientapi.ClientTenantRole
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClientSessionStateTest {
    @Test
    fun connectedStateContainsAuthoritySnapshotButNoCredentialField() {
        val state = ClientSessionState.Connected(
            AuthenticatedClientBootstrap(
                humanIdentityId = "hid_exampleopaqueidentity123",
                activeTenantId = "tnt_synthetic",
                memberships = listOf(
                    ClientTenantMembership(
                        "ctm_synthetic",
                        "tnt_synthetic",
                        ClientTenantRole.OWNER,
                    ),
                ),
                device = ClientDevice(
                    "cdev_exampledevice12345678901",
                    "sha256:" + "f".repeat(64),
                    "Synthetic Android",
                    setOf(DeviceBootstrapRole.CLIENT),
                ),
                sessionExpiresAt = Instant.parse("2030-01-01T00:15:00Z"),
                serverTime = Instant.parse("2030-01-01T00:01:00Z"),
            ),
        )

        assertTrue(state.toString().contains("tnt_synthetic"))
        assertFalse(state.toString().contains("cst_"))
        assertFalse(
            ClientSessionState::class.java.declaredClasses
                .flatMap { it.declaredFields.toList() }
                .any { it.name.contains("token", ignoreCase = true) },
        )
    }

    @Test
    fun failureStateIsBoundedToSessionFailureVocabulary() {
        val state = ClientSessionState.Failure(
            ClientSessionFailure.CLIENT_SESSION_AUTHORITY_REJECTED,
        )
        assertTrue(state.toString().contains("CLIENT_SESSION_AUTHORITY_REJECTED"))
        assertFalse(state.toString().contains("Bearer"))
    }
}
