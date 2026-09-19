package io.github.escossio.andy.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume

sealed interface ForegroundLocationResult {
    data class Success(val snapshot: ForegroundLocationSnapshot) : ForegroundLocationResult
    data object PermissionMissing : ForegroundLocationResult
    data object Unavailable : ForegroundLocationResult
    data object Timeout : ForegroundLocationResult
}

class AndroidForegroundLocationProvider(
    private val context: Context,
) {
    suspend fun current(timeoutMillis: Long = 12_000): ForegroundLocationResult {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return ForegroundLocationResult.PermissionMissing
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ForegroundLocationResult.Unavailable
        }

        val manager = context.getSystemService(LocationManager::class.java)
            ?: return ForegroundLocationResult.Unavailable
        val provider = when {
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return ForegroundLocationResult.Unavailable
        }

        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                manager.getCurrentLocation(
                    provider,
                    cancellation,
                    context.mainExecutor,
                ) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location.toResult(fine))
                    }
                }
            }
        } ?: ForegroundLocationResult.Timeout
    }

    private fun Location?.toResult(fine: Boolean): ForegroundLocationResult {
        val value = this ?: return ForegroundLocationResult.Unavailable
        if (!value.latitude.isFinite() || !value.longitude.isFinite()) {
            return ForegroundLocationResult.Unavailable
        }
        return ForegroundLocationResult.Success(
            ForegroundLocationSnapshot(
                latitude = value.latitude,
                longitude = value.longitude,
                accuracyM = value.accuracy.toDouble().coerceAtLeast(0.1),
                capturedAt = Instant.ofEpochMilli(value.time),
                precision = if (fine) LocationPrecision.PRECISE else LocationPrecision.APPROXIMATE,
            ),
        )
    }
}
