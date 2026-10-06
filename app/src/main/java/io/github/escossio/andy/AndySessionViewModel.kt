package io.github.escossio.andy

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapPublicKeyResult
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapSignatureResult
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityResult
import io.github.escossio.andy.data.clientsession.AndroidKeystoreClientSessionStore
import io.github.escossio.andy.data.deviceidentity.AndroidDeviceIdentityFactory
import io.github.escossio.andy.data.location.AndroidForegroundLocationProvider
import io.github.escossio.andy.data.location.ForegroundLocationResult
import io.github.escossio.andy.features.approvals.ApprovalCoordinator
import io.github.escossio.andy.features.command.CommandCoordinator
import io.github.escossio.andy.features.onboarding.ClientLocationFailure
import io.github.escossio.andy.features.onboarding.ClientSessionState
import io.github.escossio.andy.features.onboarding.OnboardingConfiguration
import io.github.escossio.andy.features.onboarding.OnboardingCoordinator
import io.github.escossio.andy.integrations.googleidentity.AndroidGoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.GoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.ProviderCredentialResult
import io.github.escossio.andy.integrations.googleauthorization.AndroidGoogleAuthorizationAcquirer
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationAcquirer
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationResult
import io.github.escossio.andy.sdk.clientapi.AttentionRouterClientApprovalClient
import io.github.escossio.andy.sdk.clientapi.AttentionRouterClientCommandClient
import io.github.escossio.andy.sdk.clientapi.AttentionRouterClientLocationClient
import io.github.escossio.andy.sdk.clientapi.ClientLocationObservation
import io.github.escossio.andy.sdk.clientapi.AttentionRouterClientSessionClient
import io.github.escossio.andy.sdk.clientapi.AttentionRouterDeviceBootstrapClient
import io.github.escossio.andy.sdk.clientapi.AttentionRouterHumanAuthClient
import io.github.escossio.andy.sdk.clientapi.AttentionRouterGmailConnectionClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class AndySessionViewModel private constructor(
    context: Context,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val credentialBridge =
        ActivityGoogleCredentialAcquirer(BuildConfig.GOOGLE_WEB_CLIENT_ID)
    private val authorizationBridge =
        ActivityGoogleAuthorizationBridge(BuildConfig.GOOGLE_WEB_CLIENT_ID)
    private val bootstrapIdentity = AndroidDeviceIdentityFactory.createBootstrapIdentity()
    private val locationProvider = AndroidForegroundLocationProvider(applicationContext)
    private val sessionStore = AndroidKeystoreClientSessionStore(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val approvalCoordinator = ApprovalCoordinator(
        client = AttentionRouterClientApprovalClient(
            BuildConfig.ATTENTION_ROUTER_BASE_URL,
        ),
        sessionProvider = { sessionStore.load() },
    )

    val commandCoordinator = CommandCoordinator(
        client = AttentionRouterClientCommandClient(
            BuildConfig.ATTENTION_ROUTER_BASE_URL,
        ),
        sessionProvider = { sessionStore.load() },
    )

    val coordinator = OnboardingCoordinator(
        ready = ensureDeviceIdentity(),
        config = OnboardingConfiguration(
            clientApiBaseUrl = BuildConfig.ATTENTION_ROUTER_BASE_URL,
            googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            canonicalDeviceName = Build.MODEL.take(160).ifBlank { "Android" },
        ),
        client = AttentionRouterHumanAuthClient(BuildConfig.ATTENTION_ROUTER_BASE_URL),
        provider = credentialBridge,
        bootstrapClient = AttentionRouterDeviceBootstrapClient(
            BuildConfig.ATTENTION_ROUTER_BASE_URL,
        ),
        sessionClient = AttentionRouterClientSessionClient(
            BuildConfig.ATTENTION_ROUTER_BASE_URL,
        ),
        locationClient = AttentionRouterClientLocationClient(
            BuildConfig.ATTENTION_ROUTER_BASE_URL,
        ),
        gmailClient = AttentionRouterGmailConnectionClient(
            BuildConfig.ATTENTION_ROUTER_BASE_URL,
        ),
        gmailAuthorization = authorizationBridge,
        devicePublicKeySpki = {
            when (val result = bootstrapIdentity.publicKey()) {
                is DeviceBootstrapPublicKeyResult.Ready ->
                    result.publicKeySpkiB64Url
                DeviceBootstrapPublicKeyResult.Unavailable -> null
            }
        },
        signDeviceChallenge = { challenge ->
            when (val result = bootstrapIdentity.signChallenge(challenge)) {
                is DeviceBootstrapSignatureResult.Signed ->
                    result.signatureB64Url
                DeviceBootstrapSignatureResult.Unavailable -> null
            }
        },
        sessionStore = sessionStore,
    )

    private val lifecycleRunner = SessionLifecycleRunner(
        scope = scope,
        maintainSession = { coordinator.maintainClientSession() },
        refreshConnected = {
            if (coordinator.sessionState.value is ClientSessionState.Connected) {
                approvalCoordinator.refresh()
                commandCoordinator.refresh()
            }
        },
    )

    init {
        scope.launch {
            coordinator.restoreClientSessionOnStartup()
        }
    }

    fun enterForeground() {
        lifecycleRunner.enterForeground()
    }

    fun leaveForeground() {
        lifecycleRunner.leaveForeground()
    }

    fun continueWithGoogle() = lifecycleRunner.launchAuthoritative {
        coordinator.continueWithGoogle()
    }

    fun continueWithExistingDevice() = lifecycleRunner.launchAuthoritative {
        coordinator.continueWithExistingDevice()
    }

    fun restartAuthentication() = lifecycleRunner.launchAuthoritative {
        coordinator.restartAuthentication()
    }

    fun retryDeviceBootstrap() = lifecycleRunner.launchAuthoritative {
        coordinator.retryDeviceBootstrap()
    }

    fun retryClientSession() = lifecycleRunner.launchAuthoritative {
        coordinator.retryClientSession()
    }

    fun selectTenantForSession(tenantId: String) = lifecycleRunner.launchAuthoritative {
        coordinator.selectTenantForSession(tenantId)
    }

    fun refreshApprovalsIfConnected() {
        if (coordinator.sessionState.value is ClientSessionState.Connected) {
            scope.launch { approvalCoordinator.refresh() }
        }
    }

    fun refreshCommandsIfConnected() {
        if (coordinator.sessionState.value is ClientSessionState.Connected) {
            scope.launch { commandCoordinator.refresh() }
        }
    }

    suspend fun shareCurrentLocation() {
        coordinator.beginLocationAcquisition()
        when (val result = locationProvider.current()) {
            is ForegroundLocationResult.Success -> {
                val snapshot = result.snapshot
                coordinator.shareCurrentLocation(
                    ClientLocationObservation(
                        latitude = snapshot.latitude,
                        longitude = snapshot.longitude,
                        accuracyM = snapshot.accuracyM,
                        capturedAt = snapshot.capturedAt,
                        precision = snapshot.precision.name,
                    ),
                )
            }
            ForegroundLocationResult.PermissionMissing ->
                coordinator.failLocation(ClientLocationFailure.PERMISSION_DENIED)
            ForegroundLocationResult.Unavailable ->
                coordinator.failLocation(ClientLocationFailure.LOCATION_UNAVAILABLE)
            ForegroundLocationResult.Timeout ->
                coordinator.failLocation(ClientLocationFailure.LOCATION_TIMEOUT)
        }
    }

    fun attachActivity(activity: ComponentActivity) {
        credentialBridge.attach(activity)
        authorizationBridge.attach(activity)
    }

    fun detachActivity(activity: ComponentActivity) {
        authorizationBridge.detach(activity)
        credentialBridge.detach(activity)
    }

    override fun onCleared() {
        leaveForeground()
        authorizationBridge.close()
        scope.cancel()
        super.onCleared()
    }

    private fun ensureDeviceIdentity(): Boolean =
        when (val result = AndroidDeviceIdentityFactory.create(applicationContext).ensureIdentity()) {
            is DeviceIdentityResult.Ready -> {
                Log.i(LOG_TAG, "DEVICE_IDENTITY_READY")
                true
            }
            is DeviceIdentityResult.Unavailable -> {
                Log.w(LOG_TAG, "DEVICE_IDENTITY_UNAVAILABLE:${result.reason.name}")
                false
            }
        }

    companion object {
        private const val LOG_TAG = "AndyDeviceIdentity"

        fun factory(context: Context): ViewModelProvider.Factory {
            val applicationContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AndySessionViewModel::class.java))
                    @Suppress("UNCHECKED_CAST")
                    return AndySessionViewModel(applicationContext) as T
                }
            }
        }
    }
}

/** The retained ViewModel owns auth; foreground callbacks only own maintenance. */
internal class SessionLifecycleRunner(
    private val scope: CoroutineScope,
    private val maintainSession: suspend () -> Unit,
    private val refreshConnected: suspend () -> Unit = {},
    private val authoritativeDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private var foregroundJob: Job? = null
    private var authoritativeJob: Job? = null

    fun enterForeground() {
        leaveForeground()
        foregroundJob = scope.launch {
            maintainSession()
            refreshConnected()
            while (isActive) {
                delay(30_000L)
                maintainSession()
            }
        }
    }

    fun leaveForeground() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    @Synchronized
    fun launchAuthoritative(action: suspend () -> Unit): Job? {
        if (authoritativeJob?.isActive == true) return null
        val job = scope.launch(authoritativeDispatcher, start = CoroutineStart.LAZY) { action() }
        authoritativeJob = job
        job.start()
        return job
    }
}

private class ActivityGoogleCredentialAcquirer(
    private val serverClientId: String,
) : GoogleCredentialAcquirer {
    @Volatile
    private var contextReference: WeakReference<Context>? = null

    fun attach(context: Context) {
        contextReference = WeakReference(context)
    }

    fun detach(context: Context) {
        if (contextReference?.get() === context) {
            contextReference = null
        }
    }

    override suspend fun acquire(nonce: String): ProviderCredentialResult {
        val context = contextReference?.get()
            ?: return ProviderCredentialResult.Unavailable
        return AndroidGoogleCredentialAcquirer(
            context = context,
            serverClientId = serverClientId,
        ).acquire(nonce)
    }
}


private class ActivityGoogleAuthorizationBridge(
    private val serverClientId: String,
) : GoogleAuthorizationAcquirer, AutoCloseable {
    @Volatile
    private var activityReference: WeakReference<ComponentActivity>? = null

    @Volatile
    private var delegate: AndroidGoogleAuthorizationAcquirer? = null

    @Synchronized
    fun attach(activity: ComponentActivity) {
        if (
            activityReference?.get() === activity &&
            delegate != null
        ) {
            return
        }
        delegate?.close()
        activityReference = WeakReference(activity)
        delegate = AndroidGoogleAuthorizationAcquirer(
            activity = activity,
            serverClientId = serverClientId,
        )
    }

    @Synchronized
    fun detach(activity: ComponentActivity) {
        if (activityReference?.get() === activity) {
            delegate?.close()
            delegate = null
            activityReference = null
        }
    }

    override suspend fun acquire(
        requestedScopes: Set<String>,
        forceConsent: Boolean,
    ): GoogleAuthorizationResult {
        val current = delegate
            ?: return GoogleAuthorizationResult.Unavailable
        return current.acquire(requestedScopes, forceConsent)
    }

    @Synchronized
    override fun close() {
        delegate?.close()
        delegate = null
        activityReference = null
    }
}
