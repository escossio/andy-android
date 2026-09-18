package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.sdk.clientapi.ClientDevice
import io.github.escossio.andy.sdk.clientapi.ClientTenantMembership
import io.github.escossio.andy.sdk.clientapi.ClientTenantRole
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapEstablished
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBootstrapStateTest {
    @Test
    fun establishedUiStateContainsAuthorityReferencesButNoCredential() {
        val state = DeviceBootstrapState.Established(
            DeviceBootstrapEstablished(
                humanIdentityId = "hid_synthetic",
                memberships = listOf(
                    ClientTenantMembership("ctm_synthetic", "tnt_synthetic", ClientTenantRole.OWNER),
                ),
                initialTenantId = "tnt_synthetic",
                device = ClientDevice(
                    "cdev_synthetic",
                    "sha256:" + "a".repeat(64),
                    "Synthetic Android",
                    setOf(DeviceBootstrapRole.CLIENT),
                ),
            ),
        )

        assertTrue(state.toString().contains("tnt_synthetic"))
        assertFalse(state.toString().contains("hcg_"))
        assertFalse(state.toString().contains("access" + "_token"))
        assertFalse(state.toString().contains("refresh" + "_token"))
    }
}
