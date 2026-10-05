package io.github.escossio.andy.features.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingScreenTest {
    @Test
    fun exactlyTwoMembershipsExposeOnlyTheOtherContext() {
        assertEquals(
            "tnt_target",
            dualMembershipContextTarget(
                activeTenantId = "tnt_current",
                membershipTenantIds = listOf("tnt_current", "tnt_target"),
            ),
        )
        assertEquals(
            "tnt_current",
            dualMembershipContextTarget(
                activeTenantId = "tnt_target",
                membershipTenantIds = listOf("tnt_current", "tnt_target"),
            ),
        )
    }

    @Test
    fun oneOrManyMembershipsDoNotExposeBoundedToggle() {
        assertNull(
            dualMembershipContextTarget(
                activeTenantId = "tnt_current",
                membershipTenantIds = listOf("tnt_current"),
            ),
        )
        assertNull(
            dualMembershipContextTarget(
                activeTenantId = "tnt_current",
                membershipTenantIds = listOf(
                    "tnt_current",
                    "tnt_second",
                    "tnt_third",
                ),
            ),
        )
    }

    @Test
    fun malformedMembershipSnapshotFailsClosed() {
        assertNull(
            dualMembershipContextTarget(
                activeTenantId = "tnt_current",
                membershipTenantIds = listOf("tnt_current", "tnt_current"),
            ),
        )
        assertNull(
            dualMembershipContextTarget(
                activeTenantId = "tnt_missing",
                membershipTenantIds = listOf("tnt_first", "tnt_second"),
            ),
        )
    }
}
