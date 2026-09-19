package io.github.escossio.andy.data.location

import java.time.Instant

enum class LocationPrecision {
    PRECISE,
    APPROXIMATE,
}

data class ForegroundLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val capturedAt: Instant,
    val precision: LocationPrecision,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyM.isFinite() && accuracyM > 0.0 && accuracyM <= 10_000.0)
    }
}
