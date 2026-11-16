package io.github.escossio.andy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LocationContextCollectionPolicyTest {
    @Test
    fun samplingRequiresExplicitConsentAndAllLivePermissions() {
        val p=LocationContextCollectionPolicy
        assertFalse(p.maySample(false, true, true, true))
        assertFalse(p.maySample(true, false, true, true))
        assertFalse(p.maySample(true, true, false, true))
        assertFalse(p.maySample(true, true, true, false))
        assertTrue(p.maySample(true, true, true, true))
    }

    @Test
    fun sessionRenewalStartsBeforeExpiryWithoutReusingExpiredToken() {
        val now=Instant.parse("2026-11-16T16:00:00Z")
        val p=LocationContextCollectionPolicy
        assertFalse(p.shouldRenewSession(now.plusSeconds(180), now))
        assertTrue(p.shouldRenewSession(now.plusSeconds(120), now))
        assertTrue(p.shouldRenewSession(now.minusSeconds(5), now))
    }

    @Test
    fun identityScopeMustBeExactAcrossConsentAndSampling() {
        val granted = LocationContextScope("person-A", "device-1", "tenant-one")
        assertTrue(granted.isValid())
        assertEquals(granted, LocationContextScope("person-A", "device-1", "tenant-one"))
        assertFalse(granted == LocationContextScope("person-B", "device-1", "tenant-one"))
        assertFalse(granted == LocationContextScope("person-A", "device-2", "tenant-one"))
        assertFalse(granted == LocationContextScope("person-A", "device-1", "tenant-two"))
        assertFalse(LocationContextScope("", "device-1", "tenant-one").isValid())
    }

    @Test
    fun boundedOneMinuteCaptureCadenceDoesNotClaimExactMessagePosition() {
        assertTrue(LocationContextCollectionPolicy.SAMPLE_INTERVAL_MILLIS in 30_000L..120_000L)
        assertTrue(LocationContextCollectionPolicy.POSITION_TIMEOUT_MILLIS <= 15_000L)
    }
}
