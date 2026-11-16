package io.github.escossio.andy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapPublicKeyResult
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapSignatureResult
import io.github.escossio.andy.data.clientsession.AndroidKeystoreClientSessionStore
import io.github.escossio.andy.data.deviceidentity.AndroidDeviceIdentityFactory
import io.github.escossio.andy.data.location.AndroidForegroundLocationProvider
import io.github.escossio.andy.data.location.ForegroundLocationResult
import io.github.escossio.andy.sdk.clientapi.AttentionRouterClientLocationClient
import io.github.escossio.andy.sdk.clientapi.AttentionRouterClientSessionClient
import io.github.escossio.andy.sdk.clientapi.AuthenticatedBootstrapResult
import io.github.escossio.andy.sdk.clientapi.ClientLocationObservation
import io.github.escossio.andy.sdk.clientapi.ClientLocationErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientLocationResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCompleteResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * User-controlled foreground collector of phone GPS evidence.
 *
 * Does NOT read WhatsApp, identify the author of a message, or send to contacts.
 * Auto restart is intentionally disabled: collection must visibly opt in again
 * if Android kills this process. No ACCESS_BACKGROUND_LOCATION is requested.
 */
class LocationContextTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collector: Job? = null
    private var consentedContext: LocationContextScope? = null
    private val store by lazy { AndroidKeystoreClientSessionStore(applicationContext) }
    private val provider by lazy { AndroidForegroundLocationProvider(applicationContext) }
    private val locationClient by lazy {
        AttentionRouterClientLocationClient(BuildConfig.ATTENTION_ROUTER_BASE_URL)
    }
    private val sessionClient by lazy {
        AttentionRouterClientSessionClient(BuildConfig.ATTENTION_ROUTER_BASE_URL)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || !permissionsGranted()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (collector?.isActive == true) return START_NOT_STICKY
        val chosen = LocationContextScope(
            humanIdentityId = intent.getStringExtra(EXTRA_CONSENTED_HUMAN) ?: "",
            deviceId = intent.getStringExtra(EXTRA_CONSENTED_DEVICE) ?: "",
            tenantId = intent.getStringExtra(EXTRA_CONSENTED_TENANT) ?: "",
        )
        if (!chosen.isValid()) {
            stopSelf()
            return START_NOT_STICKY
        }
        consentedContext = chosen
        try {
            startVisibleForeground()
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        } catch (_: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs().edit().putBoolean(KEY_RUNNING, true).apply()
        collector = scope.launch {
            try {
            while (isActive && prefs().getBoolean(KEY_RUNNING, false)) {
                if (!permissionsGranted()) {
                    stopSelf()
                    break
                }
                // Credentials are encrypted with Android Keystore. The app
                // never passes a session token via Intents or notifications.
                val credential = store.load()
                if (credential == null) {
                    stopSelf()
                    break
                }
                val identityScope = LocationContextScope(
                    humanIdentityId = credential.humanIdentityId,
                    deviceId = credential.deviceId,
                    tenantId = credential.tenantId,
                )
                if (consentedContext != identityScope) {
                    // Consent is tied to an exact tenant/device/person.
                    // A tenant switch cannot silently transfer tracking.
                    stopSelf()
                    break
                }
                val current = renewIfNecessary(credential)
                if (current == null || LocationContextScope(
                        humanIdentityId = current.humanIdentityId,
                        deviceId = current.deviceId,
                        tenantId = current.tenantId,
                    ) != consentedContext
                ) {
                    stopSelf()
                    break
                }
                when (val result = provider.current(
                    LocationContextCollectionPolicy.POSITION_TIMEOUT_MILLIS
                )) {
                    is ForegroundLocationResult.Success -> {
                        // Location acquisition can take several seconds.
                        // Re-read the Keystore session immediately before
                        // the network call: an intervening tenant/person
                        // switch must not publish the GPS point under the
                        // previous context.
                        val latest = store.load()
                        if (latest == null || LocationContextScope(
                                humanIdentityId = latest.humanIdentityId,
                                deviceId = latest.deviceId,
                                tenantId = latest.tenantId,
                            ) != consentedContext
                        ) {
                            stopSelf()
                            break
                        }
                        val point = result.snapshot
                        val delivered = locationClient.putContextObservation(
                            latest,
                            ClientLocationObservation(
                                latitude = point.latitude,
                                longitude = point.longitude,
                                accuracyM = point.accuracyM,
                                capturedAt = point.capturedAt,
                                precision = point.precision.name,
                            ),
                        )
                        if (delivered is ClientLocationResult.Failure &&
                            delivered.error in setOf(
                                ClientLocationErrorCode.CLIENT_LOCATION_UNAUTHENTICATED,
                                ClientLocationErrorCode.CLIENT_LOCATION_AUTHORITY_REJECTED,
                                ClientLocationErrorCode.CLIENT_LOCATION_DISABLED,
                                ClientLocationErrorCode.CLIENT_LOCATION_HISTORY_DISABLED,
                            )
                        ) {
                            // Explicit revocation, disabled server feature or
                            // expired identity means no further collection.
                            stopSelf()
                            break
                        }
                        // Other failures create evidence gaps, never fake fixes.
                    }
                    ForegroundLocationResult.PermissionMissing -> {
                        stopSelf()
                        break
                    }
                    ForegroundLocationResult.Timeout,
                    ForegroundLocationResult.Unavailable -> Unit
                }
                delay(LocationContextCollectionPolicy.SAMPLE_INTERVAL_MILLIS)
            }
            } catch (_: Exception) {
                // Fail closed; neither coordinates nor exceptions are logged.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun renewIfNecessary(
        existing: ClientSessionCredential,
    ): ClientSessionCredential? {
        if (!LocationContextCollectionPolicy.shouldRenewSession(
                existing.expiresAt, Instant.now()
            )
        ) return existing

        val identity = AndroidDeviceIdentityFactory.createBootstrapIdentity()
        val key = (identity.publicKey() as? DeviceBootstrapPublicKeyResult.Ready)
            ?.publicKeySpkiB64Url ?: return null
        val challenge = when (
            val result = sessionClient.start(
                publicKeySpkiB64Url = key, requestedTenantId = existing.tenantId,
            )
        ) {
            is ClientSessionChallengeResult.Success -> result.challenge
            is ClientSessionChallengeResult.Failure -> return null
        }
        val signature = (identity.signChallenge(challenge.challengeB64Url)
            as? DeviceBootstrapSignatureResult.Signed)?.signatureB64Url ?: return null
        val issued = when (
            val result = sessionClient.complete(
                challenge.challengeId, signature,
            )
        ) {
            is ClientSessionCompleteResult.Success -> result.session
            is ClientSessionCompleteResult.Failure -> return null
        }
        if (
            issued.humanIdentityId != existing.humanIdentityId ||
            issued.deviceId != existing.deviceId ||
            issued.tenantId != existing.tenantId
        ) return null
        val check = sessionClient.bootstrap(issued)
        if (check !is AuthenticatedBootstrapResult.Success ||
            check.bootstrap.humanIdentityId != issued.humanIdentityId ||
            check.bootstrap.activeTenantId != issued.tenantId ||
            check.bootstrap.device.deviceId != issued.deviceId ||
            check.bootstrap.sessionExpiresAt != issued.expiresAt
        ) return null
        store.save(issued)
        return issued
    }

    private fun permissionsGranted(): Boolean {
        val locationGranted =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val visibleNotification = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        return LocationContextCollectionPolicy.maySample(
            prefs().getBoolean(KEY_RUNNING, false) ||
                collector?.isActive != true,
            locationGranted,
            visibleNotification,
            true,
        )
    }

    private fun startVisibleForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Localização para contexto da Andy",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        val stop = Intent(this, LocationContextTrackingService::class.java)
            .setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Andy: localização para contexto")
            .setContentText("Registrando posições do celular. Toque para interromper.")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Parar coleta", stopPending,
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        prefs().edit().putBoolean(KEY_RUNNING, false).apply()
        collector?.cancel()
        consentedContext = null
        scope.cancel()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(PREFERENCES, MODE_PRIVATE)

    companion object {
        private const val CHANNEL_ID = "andy_location_context_v1"
        private const val NOTIFICATION_ID = 4137
        private const val PREFERENCES = "andy_location_context_v1"
        private const val KEY_RUNNING = "user_enabled"
        const val ACTION_START = "io.github.escossio.andy.location.START"
        const val ACTION_STOP = "io.github.escossio.andy.location.STOP"
        const val EXTRA_CONSENTED_HUMAN = "consented_human_identity_id"
        const val EXTRA_CONSENTED_DEVICE = "consented_device_id"
        const val EXTRA_CONSENTED_TENANT = "consented_tenant_id"

        fun isEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)
    }
}
