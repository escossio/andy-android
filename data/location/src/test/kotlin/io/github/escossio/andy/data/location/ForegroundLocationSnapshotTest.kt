package io.github.escossio.andy.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class ForegroundLocationSnapshotTest {
    @Test
    fun validSnapshotPreservesForegroundObservation() {
        val snapshot = ForegroundLocationSnapshot(
            latitude = -3.765,
            longitude = -38.60,
            accuracyM = 18.5,
            capturedAt = Instant.parse("2026-09-19T01:00:00Z"),
            precision = LocationPrecision.PRECISE,
        )

        assertEquals(-3.765, snapshot.latitude, 0.0)
        assertEquals(LocationPrecision.PRECISE, snapshot.precision)
    }

    @Test
    fun invalidAccuracyIsRejectedLocally() {
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundLocationSnapshot(
                latitude = 0.0,
                longitude = 0.0,
                accuracyM = 0.0,
                capturedAt = Instant.EPOCH,
                precision = LocationPrecision.APPROXIMATE,
            )
        }
    }
}
