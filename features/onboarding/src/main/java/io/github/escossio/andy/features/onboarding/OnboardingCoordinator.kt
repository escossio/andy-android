package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.core.humanidentity.HumanIdentityEngine
import io.github.escossio.andy.core.humanidentity.HumanIdentityFailure
import io.github.escossio.andy.core.humanidentity.HumanIdentityReference
import io.github.escossio.andy.core.humanidentity.HumanIdentityState
import io.github.escossio.andy.integrations.googleidentity.GoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.ProviderCredentialResult
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationAcquirer
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationResult
import io.github.escossio.andy.sdk.clientapi.AuthenticatedBootstrapResult
import io.github.escossio.andy.sdk.clientapi.ChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientLocationClient
import io.github.escossio.andy.sdk.clientapi.ClientLocationErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientLocationObservation
import io.github.escossio.andy.sdk.clientapi.ClientLocationResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionClient
import io.github.escossio.andy.sdk.clientapi.ClientSessionCompleteResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import io.github.escossio.andy.sdk.clientapi.ClientSessionErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientSessionStore
import io.github.escossio.andy.sdk.clientapi.ContinuationResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapChallengeResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapClient
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapCompleteResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapErrorCode
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapRole
import io.github.escossio.andy.sdk.clientapi.HumanAuthClient
import io.github.escossio.andy.sdk.clientapi.HumanAuthContinuationGrant
import io.github.escossio.andy.sdk.clientapi.HumanAuthErrorCode
import io.github.escossio.andy.sdk.clientapi.NoopClientSessionStore
import io.github.escossio.andy.sdk.clientapi.GMAIL_READONLY_SCOPE
import io.github.escossio.andy.sdk.clientapi.GmailConnectionClient
import io.github.escossio.andy.sdk.clientapi.GmailConnectionErrorCode
import io.github.escossio.andy.sdk.clientapi.GmailConnectionResult
import io.github.escossio.andy.sdk.clientapi.GmailConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.logging.Logger

data class OnboardingConfiguration(
    val clientApiBaseUrl: String,
    val googleWebClientId: String,
    val canonicalDeviceName: String,
)

class OnboardingCoordinator(
    ready: Boolean,
    private val config: OnboardingConfiguration,
    private val client: HumanAuthClient,
    private val provider: GoogleCredentialAcquirer,
    private val bootstrapClient: DeviceBootstrapClient,
    private val sessionClient: ClientSessionClient,
    private val locationClient: ClientLocationClient? = null,
    private val gmailClient: GmailConnectionClient? = null,
    private val gmailAuthorization: GoogleAuthorizationAcquirer? = null,
    private val devicePublicKeySpki: () -> String?,
    private val signDeviceChallenge: (String) -> String?,
    private val sessionStore: ClientSessionStore = NoopClientSessionStore,
    private val now: () -> Instant = Instant::now,
    private val engine: HumanIdentityEngine = HumanIdentityEngine(ready),
    private val sessionTelemetry: (String, Map<String, String>) -> Unit =
        ::logSecureSessionEvent,
) {
    private val mutable = MutableStateFlow(engine.state)
    val state: StateFlow<HumanIdentityState> = mutable.asStateFlow()

    private val bootstrapMutable =
        MutableStateFlow<DeviceBootstrapState>(DeviceBootstrapState.Idle)
    val bootstrapState: StateFlow<DeviceBootstrapState> = bootstrapMutable.asStateFlow()

    private val sessionMutable =
        MutableStateFlow<ClientSessionState>(ClientSessionState.Idle)
    val sessionState: StateFlow<ClientSessionState> = sessionMutable.asStateFlow()

    private val locationMutable =
        MutableStateFlow<ClientLocationState>(ClientLocationState.Idle)
    val locationState: StateFlow<ClientLocationState> = locationMutable.asStateFlow()

    private val gmailMutable =
        MutableStateFlow<GmailConnectionState>(GmailConnectionState.Idle)
    val gmailState: StateFlow<GmailConnectionState> = gmailMutable.asStateFlow()

    private var continuationGrant: HumanAuthContinuationGrant? = null
    private var validatedIdentity: HumanIdentityReference? = null
    private var clientSession: ClientSessionCredential? = null
    private val deviceReady = ready
    private val sessionMutex = Mutex()
    private val gmailMutex = Mutex()

    fun takeContinuationGrant(): HumanAuthContinuationGrant? {
        val grant = continuationGrant
        continuationGrant = null
        return grant
    }

    suspend fun restoreClientSessionOnStartup() {
        sessionMutex.withLock {
            if (!deviceReady || sessionMutable.value is ClientSessionState.Connected) {
                return@withLock
            }
            restoreOrEstablish(automatic = true)
        }
    }

    suspend fun continueWithGoogle() {
        val reset = sessionMutex.withLock {
            if (!clearStoredSession()) return@withLock false
            continuationGrant = null
            validatedIdentity = null
            clientSession = null
            bootstrapMutable.value = DeviceBootstrapState.Idle
            transitionSession(ClientSessionState.Idle, "GOOGLE_REAUTH")
            gmailMutable.value = GmailConnectionState.Idle
            true
        }
        if (!reset) return

        if (engine.state == HumanIdentityState.DeviceIdentityUnavailable) {
            mutable.value = engine.begin()
            return
        }
        if (
            config.clientApiBaseUrl.isBlank() ||
            config.googleWebClientId.isBlank() ||
            config.canonicalDeviceName.isBlank()
        ) {
            mutable.value = engine.fail(HumanIdentityFailure.CONFIGURATION_MISSING)
            return
        }

        mutable.value = engine.begin()
        val challenge = when (val result = client.requestChallenge()) {
            is ChallengeResult.Success -> result.challenge
            is ChallengeResult.Failure -> {
                mutable.value = engine.fail(result.error.failure())
                return
            }
        }
        mutable.value = engine.challengeReceived()

        when (val credential = provider.acquire(challenge.nonce)) {
            ProviderCredentialResult.Cancelled ->
                mutable.value = engine.fail(HumanIdentityFailure.PROVIDER_CANCELLED)
            ProviderCredentialResult.Unavailable ->
                mutable.value = engine.fail(HumanIdentityFailure.PROVIDER_UNAVAILABLE)
            is ProviderCredentialResult.Token -> {
                mutable.value = engine.providerCredentialReceived()
                when (
                    val continued = client.verifyAndContinue(
                        challenge.challengeId,
                        credential.idToken,
                    )
                ) {
                    is ContinuationResult.Success -> {
                        continuationGrant = continued.continuation.grant
                        validatedIdentity = continued.continuation.identityReference
                        mutable.value = engine.backendValidated(
                            continued.continuation.identityReference,
                        )
                        continueDeviceBootstrap()
                    }
                    is ContinuationResult.Failure ->
                        mutable.value = engine.fail(continued.error.failure())
                }
            }
        }
    }

    suspend fun continueWithExistingDevice() {
        sessionMutex.withLock {
            if (sessionMutable.value is ClientSessionState.Connected) return@withLock
            restoreOrEstablish(automatic = false)
        }
    }

    fun beginLocationAcquisition() {
        if (sessionMutable.value is ClientSessionState.Connected) {
            locationMutable.value = ClientLocationState.Acquiring
        } else {
            locationMutable.value = ClientLocationState.Failure(
                ClientLocationFailure.CLIENT_LOCATION_UNAUTHENTICATED,
            )
        }
    }

    fun failLocation(reason: ClientLocationFailure) {
        locationMutable.value = ClientLocationState.Failure(reason)
    }

    suspend fun refreshGmailConnection() {
        gmailMutex.withLock {
            refreshGmailConnectionLocked()
        }
    }

    suspend fun connectGmail() {
        gmailMutex.withLock {
            val session = requireGmailSession() ?: return@withLock
            val client = gmailClient
                ?: return@withLock failGmail(
                    GmailConnectionFailure.GMAIL_CONNECTION_UNAVAILABLE,
                )
            val authorization = gmailAuthorization
                ?: return@withLock failGmail(
                    GmailConnectionFailure.PROVIDER_UNAVAILABLE,
                )

            val first = authorizeGmail(
                authorization = authorization,
                forceConsent = false,
            ) ?: return@withLock
            gmailMutable.value = GmailConnectionState.Connecting

            val firstResult = first.withCode { code ->
                client.connectGmail(session, code)
            }
            if (
                firstResult is GmailConnectionResult.Failure &&
                firstResult.error == GmailConnectionErrorCode.GMAIL_REFRESH_TOKEN_REQUIRED
            ) {
                val retry = authorizeGmail(
                    authorization = authorization,
                    forceConsent = true,
                ) ?: return@withLock
                gmailMutable.value = GmailConnectionState.Connecting
                applyGmailResult(
                    retry.withCode { code ->
                        client.connectGmail(session, code)
                    },
                )
                return@withLock
            }
            applyGmailResult(firstResult)
        }
    }

    suspend fun disconnectGmail() {
        gmailMutex.withLock {
            val session = requireGmailSession() ?: return@withLock
            val client = gmailClient
                ?: return@withLock failGmail(
                    GmailConnectionFailure.GMAIL_CONNECTION_UNAVAILABLE,
                )
            gmailMutable.value = GmailConnectionState.Disconnecting
            applyGmailResult(client.disconnectGmail(session))
        }
    }

    suspend fun shareCurrentLocation(observation: ClientLocationObservation) {
        val session = clientSession
        val client = locationClient
        if (
            session == null ||
            client == null ||
            sessionMutable.value !is ClientSessionState.Connected
        ) {
            locationMutable.value = ClientLocationState.Failure(
                ClientLocationFailure.CLIENT_LOCATION_UNAUTHENTICATED,
            )
            return
        }

        locationMutable.value = ClientLocationState.Sharing
        val written = when (val result = client.putCurrent(session, observation)) {
            is ClientLocationResult.Success -> result.snapshot
            is ClientLocationResult.Failure -> {
                locationMutable.value = ClientLocationState.Failure(result.error.locationFailure())
                return
            }
        }

        val readback = when (val result = client.getCurrent(session)) {
            is ClientLocationResult.Success -> result.snapshot
            is ClientLocationResult.Failure -> {
                locationMutable.value = ClientLocationState.Failure(result.error.locationFailure())
                return
            }
        }

        if (
            written.locationSnapshotId != readback.locationSnapshotId ||
            readback.humanIdentityId != session.humanIdentityId ||
            readback.deviceId != session.deviceId ||
            readback.tenantId != session.tenantId
        ) {
            locationMutable.value = ClientLocationState.Failure(
                ClientLocationFailure.AUTHORITY_MISMATCH,
            )
            return
        }

        locationMutable.value = ClientLocationState.Shared(
            accuracyM = readback.accuracyM,
            capturedAt = readback.capturedAt,
        )
    }

    suspend fun retryClientSession() {
        sessionMutex.withLock {
            emitSessionEvent(
                "SECURE_RETRY_CLICK",
                "state" to sessionMutable.value.telemetryName(),
                "has_session" to (clientSession != null).toString(),
            )
            if (sessionMutable.value is ClientSessionState.Connected) return@withLock

            val current = clientSession
            if (current == null) {
                restoreOrEstablish(automatic = false)
                return@withLock
            }

            if (!current.expiresAt.isAfter(now())) {
                val requestedTenantId = current.tenantId
                emitSessionEvent("CLIENT_SESSION_VALIDATION", "result" to "EXPIRED")
                establishFreshSession(
                    automatic = false,
                    requestedTenantIdOverride = requestedTenantId,
                    trigger = SessionRefreshTrigger.EXPIRED,
                )
                return@withLock
            }

            emitSessionEvent("CLIENT_SESSION_VALIDATION", "result" to "PRESENT_UNEXPIRED")
            when (bootstrapCurrentSession()) {
                SessionBootstrapOutcome.CONNECTED,
                SessionBootstrapOutcome.FAILURE -> Unit
                SessionBootstrapOutcome.INVALID -> {
                    val requestedTenantId = current.tenantId
                    establishFreshSession(
                        automatic = false,
                        requestedTenantIdOverride = requestedTenantId,
                        trigger = SessionRefreshTrigger.REJECTED,
                    )
                }
            }
        }
    }

    suspend fun retryDeviceBootstrap() {
        if (
            continuationGrant == null ||
            validatedIdentity == null ||
            engine.state !is HumanIdentityState.Validated
        ) {
            bootstrapMutable.value = DeviceBootstrapState.Failure(
                DeviceBootstrapFailure.DEVICE_BOOTSTRAP_GRANT_REJECTED,
            )
            return
        }
        continueDeviceBootstrap()
    }

    suspend fun restartAuthentication() {
        val reset = sessionMutex.withLock {
            if (!clearStoredSession()) return@withLock false
            clientSession = null
            transitionSession(ClientSessionState.Idle, "AUTH_RESTART")
            gmailMutable.value = GmailConnectionState.Idle
            true
        }
        if (!reset) return

        continuationGrant = null
        validatedIdentity = null
        bootstrapMutable.value = DeviceBootstrapState.Idle
        mutable.value = engine.retry()
    }

    private suspend fun continueDeviceBootstrap() {
        val grant = continuationGrant
            ?: return failBootstrap(DeviceBootstrapFailure.DEVICE_BOOTSTRAP_GRANT_REJECTED)
        val identity = validatedIdentity
            ?: return failBootstrap(DeviceBootstrapFailure.UNEXPECTED_RESPONSE)

        bootstrapMutable.value = DeviceBootstrapState.Establishing
        val publicKey = try {
            devicePublicKeySpki()
        } catch (_: Exception) {
            null
        } ?: return failBootstrap(DeviceBootstrapFailure.DEVICE_IDENTITY_UNAVAILABLE)

        val challenge = when (
            val result = bootstrapClient.start(
                grant = grant,
                publicKeySpkiB64Url = publicKey,
                canonicalDeviceName = config.canonicalDeviceName,
                roles = setOf(
                    DeviceBootstrapRole.CLIENT,
                    DeviceBootstrapRole.CAPABILITY_NODE,
                ),
            )
        ) {
            is DeviceBootstrapChallengeResult.Success -> result.challenge
            is DeviceBootstrapChallengeResult.Failure ->
                return failBootstrap(result.error.failure())
        }

        val signature = try {
            signDeviceChallenge(challenge.challengeB64Url)
        } catch (_: Exception) {
            null
        } ?: return failBootstrap(DeviceBootstrapFailure.DEVICE_IDENTITY_UNAVAILABLE)

        when (
            val result = bootstrapClient.complete(
                challenge.challengeId,
                signature,
            )
        ) {
            is DeviceBootstrapCompleteResult.Success -> {
                continuationGrant = null
                if (result.established.humanIdentityId != identity.opaqueId) {
                    failBootstrap(DeviceBootstrapFailure.HUMAN_IDENTITY_MISMATCH)
                } else {
                    bootstrapMutable.value =
                        DeviceBootstrapState.Established(result.established)
                    sessionMutex.withLock {
                        establishFreshSession(
                            automatic = false,
                            trigger = SessionRefreshTrigger.DEVICE_BOOTSTRAP,
                        )
                    }
                }
            }
            is DeviceBootstrapCompleteResult.Failure ->
                failBootstrap(result.error.failure())
        }
    }

    private suspend fun restoreOrEstablish(automatic: Boolean) {
        val stored = try {
            sessionStore.load()
        } catch (_: Exception) {
            emitSessionEvent("CLIENT_SESSION_LOAD", "result" to "ERROR")
            failSession(ClientSessionFailure.SESSION_STORAGE_UNAVAILABLE)
            return
        }

        if (stored == null) {
            emitSessionEvent("CLIENT_SESSION_LOAD", "result" to "ABSENT")
            establishFreshSession(
                automatic = automatic,
                trigger = SessionRefreshTrigger.NO_STORED_SESSION,
            )
            return
        }

        emitSessionEvent("CLIENT_SESSION_LOAD", "result" to "PRESENT")
        clientSession = stored
        if (!stored.expiresAt.isAfter(now())) {
            val requestedTenantId = stored.tenantId
            emitSessionEvent("CLIENT_SESSION_VALIDATION", "result" to "EXPIRED")
            establishFreshSession(
                automatic = automatic,
                requestedTenantIdOverride = requestedTenantId,
                trigger = SessionRefreshTrigger.EXPIRED,
            )
            return
        }

        emitSessionEvent("CLIENT_SESSION_VALIDATION", "result" to "PRESENT_UNEXPIRED")
        transitionSession(ClientSessionState.Restoring, "STORED_SESSION_PRESENT")
        when (bootstrapCurrentSession()) {
            SessionBootstrapOutcome.CONNECTED,
            SessionBootstrapOutcome.FAILURE -> Unit
            SessionBootstrapOutcome.INVALID -> {
                val requestedTenantId = stored.tenantId
                establishFreshSession(
                    automatic = automatic,
                    requestedTenantIdOverride = requestedTenantId,
                    trigger = SessionRefreshTrigger.REJECTED,
                )
            }
        }
    }

    private suspend fun establishFreshSession(
        automatic: Boolean,
        requestedTenantIdOverride: String? = null,
        trigger: SessionRefreshTrigger = SessionRefreshTrigger.NO_STORED_SESSION,
    ) {
        if (!deviceReady) {
            emitSessionEvent("CLIENT_IDENTITY_LOAD", "result" to "UNAVAILABLE")
            failSession(ClientSessionFailure.DEVICE_IDENTITY_UNAVAILABLE)
            return
        }

        val previousSession = clientSession
        transitionSession(ClientSessionState.Establishing, "SESSION_REFRESH")
        val publicKey = try {
            devicePublicKeySpki()
        } catch (_: Exception) {
            null
        }
        if (publicKey == null) {
            emitSessionEvent("CLIENT_IDENTITY_LOAD", "result" to "UNAVAILABLE")
            failSession(ClientSessionFailure.DEVICE_IDENTITY_UNAVAILABLE)
            return
        }
        emitSessionEvent("CLIENT_IDENTITY_LOAD", "result" to "READY")

        val requestedTenantId =
            requestedTenantIdOverride
                ?: (bootstrapMutable.value as? DeviceBootstrapState.Established)
                    ?.authority
                    ?.initialTenantId
        emitSessionEvent(
            "CLIENT_SESSION_REFRESH_START",
            "automatic" to automatic.toString(),
            "tenant_hint" to if (requestedTenantId == null) "ABSENT" else "PRESENT",
            "trigger" to trigger.name,
        )

        emitSessionEvent(
            "CLIENT_SESSION_REFRESH_HTTP",
            "operation" to "CHALLENGE_START",
            "phase" to "START",
        )
        val challenge = when (
            val result = sessionClient.start(
                publicKeySpkiB64Url = publicKey,
                requestedTenantId = requestedTenantId,
            )
        ) {
            is ClientSessionChallengeResult.Success -> {
                emitSessionEvent(
                    "CLIENT_SESSION_REFRESH_HTTP_RESULT",
                    "operation" to "CHALLENGE_START",
                    "result" to "SUCCESS",
                )
                result.challenge
            }
            is ClientSessionChallengeResult.Failure -> {
                emitSessionEvent(
                    "CLIENT_SESSION_REFRESH_HTTP_RESULT",
                    "category" to result.error.name,
                    "operation" to "CHALLENGE_START",
                    "result" to "FAILURE",
                )
                if (
                    automatic &&
                    result.error == ClientSessionErrorCode.CLIENT_SESSION_DEVICE_REJECTED
                ) {
                    transitionSession(ClientSessionState.Idle, "UNKNOWN_DEVICE")
                } else {
                    failSession(result.error.failure())
                }
                return
            }
        }

        val signature = try {
            signDeviceChallenge(challenge.challengeB64Url)
        } catch (_: Exception) {
            null
        }
        if (signature == null) {
            emitSessionEvent("CLIENT_SESSION_SIGNATURE", "result" to "UNAVAILABLE")
            failSession(ClientSessionFailure.DEVICE_IDENTITY_UNAVAILABLE)
            return
        }
        emitSessionEvent("CLIENT_SESSION_SIGNATURE", "result" to "SUCCESS")

        emitSessionEvent(
            "CLIENT_SESSION_REFRESH_HTTP",
            "operation" to "CHALLENGE_COMPLETE",
            "phase" to "START",
        )
        val issued = when (
            val result = sessionClient.complete(
                challenge.challengeId,
                signature,
            )
        ) {
            is ClientSessionCompleteResult.Success -> {
                emitSessionEvent(
                    "CLIENT_SESSION_REFRESH_HTTP_RESULT",
                    "operation" to "CHALLENGE_COMPLETE",
                    "result" to "SUCCESS",
                )
                result.session
            }
            is ClientSessionCompleteResult.Failure -> {
                emitSessionEvent(
                    "CLIENT_SESSION_REFRESH_HTTP_RESULT",
                    "category" to result.error.name,
                    "operation" to "CHALLENGE_COMPLETE",
                    "result" to "FAILURE",
                )
                failSession(result.error.failure())
                return
            }
        }

        val established =
            (bootstrapMutable.value as? DeviceBootstrapState.Established)?.authority
        if (
            established != null &&
            (
                established.humanIdentityId != issued.humanIdentityId ||
                    established.device.deviceId != issued.deviceId ||
                    (
                        established.initialTenantId != null &&
                            established.initialTenantId != issued.tenantId
                )
            )
        ) {
            clientSession = previousSession
            failSession(ClientSessionFailure.SESSION_BOOTSTRAP_MISMATCH)
            return
        }

        clientSession = issued
        if (bootstrapCurrentSession() == SessionBootstrapOutcome.INVALID) {
            clientSession = previousSession
        }
    }

    private suspend fun bootstrapCurrentSession(): SessionBootstrapOutcome {
        val session = clientSession
            ?: run {
                failSession(ClientSessionFailure.CLIENT_SESSION_UNAUTHENTICATED)
                return SessionBootstrapOutcome.INVALID
            }

        transitionSession(ClientSessionState.LoadingBootstrap, "BOOTSTRAP_START")
        emitSessionEvent(
            "CLIENT_SESSION_REFRESH_HTTP",
            "operation" to "BOOTSTRAP",
            "phase" to "START",
        )
        return when (val result = sessionClient.bootstrap(session)) {
            is AuthenticatedBootstrapResult.Success -> {
                emitSessionEvent(
                    "CLIENT_SESSION_REFRESH_HTTP_RESULT",
                    "operation" to "BOOTSTRAP",
                    "result" to "SUCCESS",
                )
                val bootstrap = result.bootstrap
                if (
                    bootstrap.humanIdentityId != session.humanIdentityId ||
                    bootstrap.activeTenantId != session.tenantId ||
                    bootstrap.device.deviceId != session.deviceId ||
                    bootstrap.sessionExpiresAt != session.expiresAt ||
                    bootstrap.memberships.none { it.tenantId == bootstrap.activeTenantId }
                ) {
                    failSession(ClientSessionFailure.SESSION_BOOTSTRAP_MISMATCH)
                    SessionBootstrapOutcome.INVALID
                } else {
                    try {
                        sessionStore.save(session)
                    } catch (_: Exception) {
                        failSession(ClientSessionFailure.SESSION_STORAGE_UNAVAILABLE)
                        return SessionBootstrapOutcome.FAILURE
                    }
                    emitSessionEvent("CLIENT_SESSION_ACCEPTED", "persisted" to "true")
                    transitionSession(
                        ClientSessionState.Connected(bootstrap),
                        "BOOTSTRAP_ACCEPTED",
                    )
                    SessionBootstrapOutcome.CONNECTED
                }
            }
            is AuthenticatedBootstrapResult.Failure -> {
                emitSessionEvent(
                    "CLIENT_SESSION_REFRESH_HTTP_RESULT",
                    "category" to result.error.name,
                    "operation" to "BOOTSTRAP",
                    "result" to "FAILURE",
                )
                failSession(result.error.failure())
                if (result.error.invalidatesStoredCredential()) {
                    emitSessionEvent(
                        "CLIENT_SESSION_VALIDATION",
                        "category" to result.error.name,
                        "result" to "REJECTED",
                    )
                    SessionBootstrapOutcome.INVALID
                } else {
                    SessionBootstrapOutcome.FAILURE
                }
            }
        }
    }

    private suspend fun refreshGmailConnectionLocked() {
        val session = requireGmailSession() ?: return
        val client = gmailClient
            ?: return failGmail(
                GmailConnectionFailure.GMAIL_CONNECTION_UNAVAILABLE,
            )
        gmailMutable.value = GmailConnectionState.Loading
        applyGmailResult(client.getGmailConnection(session))
    }

    private fun requireGmailSession(): ClientSessionCredential? {
        val session = clientSession
        if (
            session == null ||
            sessionMutable.value !is ClientSessionState.Connected
        ) {
            failGmail(
                GmailConnectionFailure.CLIENT_SESSION_UNAUTHENTICATED,
            )
            return null
        }
        return session
    }

    private suspend fun authorizeGmail(
        authorization: GoogleAuthorizationAcquirer,
        forceConsent: Boolean,
    ) = when (
        val result = authorization.acquire(
            requestedScopes = setOf(GMAIL_READONLY_SCOPE),
            forceConsent = forceConsent,
        )
    ) {
        GoogleAuthorizationResult.Cancelled -> {
            failGmail(GmailConnectionFailure.PROVIDER_CANCELLED)
            null
        }
        GoogleAuthorizationResult.Unavailable -> {
            failGmail(GmailConnectionFailure.PROVIDER_UNAVAILABLE)
            null
        }
        is GoogleAuthorizationResult.Authorized -> result.code
    }

    private fun applyGmailResult(result: GmailConnectionResult) {
        gmailMutable.value = when (result) {
            is GmailConnectionResult.Success -> when (result.connection.status) {
                GmailConnectionStatus.CONNECTED ->
                    GmailConnectionState.Connected(result.connection.grantedScopes)
                GmailConnectionStatus.DISCONNECTED ->
                    GmailConnectionState.Disconnected
            }
            is GmailConnectionResult.Failure ->
                GmailConnectionState.Failure(result.error.gmailFailure())
        }
    }

    private fun failGmail(reason: GmailConnectionFailure) {
        gmailMutable.value = GmailConnectionState.Failure(reason)
    }

    private suspend fun clearStoredSession(): Boolean = try {
        sessionStore.clear()
        true
    } catch (_: Exception) {
        failSession(ClientSessionFailure.SESSION_STORAGE_UNAVAILABLE)
        false
    }

    private fun failSession(reason: ClientSessionFailure) {
        emitSessionEvent("CLIENT_SESSION_REJECTED", "category" to reason.name)
        transitionSession(ClientSessionState.Failure(reason), "SESSION_FAILURE")
    }

    private fun transitionSession(next: ClientSessionState, cause: String) {
        val previous = sessionMutable.value
        sessionMutable.value = next
        emitSessionEvent(
            "SECURE_UI_STATE_CHANGE",
            "cause" to cause,
            "from" to previous.telemetryName(),
            "to" to next.telemetryName(),
        )
    }

    private fun emitSessionEvent(event: String, vararg fields: Pair<String, String>) {
        try {
            sessionTelemetry(event, mapOf(*fields))
        } catch (_: Exception) {
            // Telemetry must never change authentication behavior.
        }
    }

    private fun ClientSessionState.telemetryName() = when (this) {
        ClientSessionState.Idle -> "IDLE"
        ClientSessionState.Restoring -> "RESTORING"
        ClientSessionState.Establishing -> "ESTABLISHING"
        ClientSessionState.LoadingBootstrap -> "LOADING_BOOTSTRAP"
        is ClientSessionState.Connected -> "CONNECTED"
        is ClientSessionState.Failure -> "FAILURE"
    }

    private fun failBootstrap(reason: DeviceBootstrapFailure) {
        bootstrapMutable.value = DeviceBootstrapState.Failure(reason)
    }

    private fun HumanAuthErrorCode.failure() = when (this) {
        HumanAuthErrorCode.NETWORK_FAILURE -> HumanIdentityFailure.NETWORK_FAILURE
        HumanAuthErrorCode.HUMAN_AUTH_DISABLED -> HumanIdentityFailure.HUMAN_AUTH_DISABLED
        HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_NOT_FOUND ->
            HumanIdentityFailure.HUMAN_AUTH_CHALLENGE_NOT_FOUND
        HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_EXPIRED ->
            HumanIdentityFailure.CHALLENGE_EXPIRED
        HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_CONSUMED ->
            HumanIdentityFailure.CHALLENGE_CONSUMED
        HumanAuthErrorCode.HUMAN_AUTH_CREDENTIAL_REJECTED ->
            HumanIdentityFailure.CREDENTIAL_REJECTED
        HumanAuthErrorCode.HUMAN_AUTH_NONCE_MISMATCH -> HumanIdentityFailure.NONCE_MISMATCH
        HumanAuthErrorCode.HUMAN_AUTH_PROVIDER_UNAVAILABLE ->
            HumanIdentityFailure.PROVIDER_UNAVAILABLE
        HumanAuthErrorCode.UNEXPECTED_RESPONSE -> HumanIdentityFailure.UNEXPECTED_RESPONSE
    }

    private fun DeviceBootstrapErrorCode.failure() = when (this) {
        DeviceBootstrapErrorCode.NETWORK_FAILURE -> DeviceBootstrapFailure.NETWORK_FAILURE
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_GRANT_REJECTED ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_GRANT_REJECTED
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_NOT_FOUND ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_CHALLENGE_NOT_FOUND
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_EXPIRED ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_CHALLENGE_EXPIRED
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_CONSUMED ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_CHALLENGE_CONSUMED
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_DEVICE_KEY_INVALID ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_DEVICE_KEY_INVALID
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_SIGNATURE_INVALID ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_SIGNATURE_INVALID
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_MEMBERSHIP_CONFLICT ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_MEMBERSHIP_CONFLICT
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_DEVICE_CONFLICT ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_DEVICE_CONFLICT
        DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_UNAVAILABLE ->
            DeviceBootstrapFailure.DEVICE_BOOTSTRAP_UNAVAILABLE
        DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE ->
            DeviceBootstrapFailure.UNEXPECTED_RESPONSE
    }

    private fun ClientSessionErrorCode.failure() = when (this) {
        ClientSessionErrorCode.NETWORK_FAILURE ->
            ClientSessionFailure.NETWORK_FAILURE
        ClientSessionErrorCode.CLIENT_SESSION_DEVICE_REJECTED ->
            ClientSessionFailure.CLIENT_SESSION_DEVICE_REJECTED
        ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_NOT_FOUND ->
            ClientSessionFailure.CLIENT_SESSION_CHALLENGE_NOT_FOUND
        ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_EXPIRED ->
            ClientSessionFailure.CLIENT_SESSION_CHALLENGE_EXPIRED
        ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_CONSUMED ->
            ClientSessionFailure.CLIENT_SESSION_CHALLENGE_CONSUMED
        ClientSessionErrorCode.CLIENT_SESSION_SIGNATURE_INVALID ->
            ClientSessionFailure.CLIENT_SESSION_SIGNATURE_INVALID
        ClientSessionErrorCode.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED ->
            ClientSessionFailure.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED
        ClientSessionErrorCode.CLIENT_SESSION_TENANT_FORBIDDEN ->
            ClientSessionFailure.CLIENT_SESSION_TENANT_FORBIDDEN
        ClientSessionErrorCode.CLIENT_SESSION_UNAUTHENTICATED ->
            ClientSessionFailure.CLIENT_SESSION_UNAUTHENTICATED
        ClientSessionErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED ->
            ClientSessionFailure.CLIENT_SESSION_AUTHORITY_REJECTED
        ClientSessionErrorCode.CLIENT_SESSION_UNAVAILABLE ->
            ClientSessionFailure.CLIENT_SESSION_UNAVAILABLE
        ClientSessionErrorCode.UNEXPECTED_RESPONSE ->
            ClientSessionFailure.UNEXPECTED_RESPONSE
    }

    private fun ClientLocationErrorCode.locationFailure() = when (this) {
        ClientLocationErrorCode.NETWORK_FAILURE -> ClientLocationFailure.NETWORK_FAILURE
        ClientLocationErrorCode.CLIENT_LOCATION_DISABLED -> ClientLocationFailure.CLIENT_LOCATION_DISABLED
        ClientLocationErrorCode.CLIENT_LOCATION_INVALID -> ClientLocationFailure.CLIENT_LOCATION_INVALID
        ClientLocationErrorCode.CLIENT_LOCATION_STALE -> ClientLocationFailure.CLIENT_LOCATION_STALE
        ClientLocationErrorCode.CLIENT_LOCATION_FUTURE -> ClientLocationFailure.CLIENT_LOCATION_FUTURE
        ClientLocationErrorCode.CLIENT_LOCATION_UNAUTHENTICATED -> ClientLocationFailure.CLIENT_LOCATION_UNAUTHENTICATED
        ClientLocationErrorCode.CLIENT_LOCATION_AUTHORITY_REJECTED -> ClientLocationFailure.CLIENT_LOCATION_AUTHORITY_REJECTED
        ClientLocationErrorCode.CLIENT_LOCATION_NOT_FOUND -> ClientLocationFailure.CLIENT_LOCATION_NOT_FOUND
        ClientLocationErrorCode.CLIENT_LOCATION_UNAVAILABLE -> ClientLocationFailure.CLIENT_LOCATION_UNAVAILABLE
        ClientLocationErrorCode.UNEXPECTED_RESPONSE -> ClientLocationFailure.UNEXPECTED_RESPONSE
    }

    private fun GmailConnectionErrorCode.gmailFailure() = when (this) {
        GmailConnectionErrorCode.NETWORK_FAILURE ->
            GmailConnectionFailure.NETWORK_FAILURE
        GmailConnectionErrorCode.GMAIL_CONNECTION_DISABLED ->
            GmailConnectionFailure.GMAIL_CONNECTION_DISABLED
        GmailConnectionErrorCode.GMAIL_AUTHORIZATION_REJECTED ->
            GmailConnectionFailure.GMAIL_AUTHORIZATION_REJECTED
        GmailConnectionErrorCode.GMAIL_REFRESH_TOKEN_REQUIRED ->
            GmailConnectionFailure.GMAIL_REFRESH_TOKEN_REQUIRED
        GmailConnectionErrorCode.GMAIL_PROVIDER_UNAVAILABLE ->
            GmailConnectionFailure.GMAIL_PROVIDER_UNAVAILABLE
        GmailConnectionErrorCode.GMAIL_CONNECTION_CONFLICT ->
            GmailConnectionFailure.GMAIL_CONNECTION_CONFLICT
        GmailConnectionErrorCode.GMAIL_CONNECTION_UNAVAILABLE ->
            GmailConnectionFailure.GMAIL_CONNECTION_UNAVAILABLE
        GmailConnectionErrorCode.CLIENT_SESSION_DEVICE_REJECTED ->
            GmailConnectionFailure.CLIENT_SESSION_DEVICE_REJECTED
        GmailConnectionErrorCode.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED ->
            GmailConnectionFailure.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED
        GmailConnectionErrorCode.CLIENT_SESSION_TENANT_FORBIDDEN ->
            GmailConnectionFailure.CLIENT_SESSION_TENANT_FORBIDDEN
        GmailConnectionErrorCode.CLIENT_SESSION_UNAUTHENTICATED ->
            GmailConnectionFailure.CLIENT_SESSION_UNAUTHENTICATED
        GmailConnectionErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED ->
            GmailConnectionFailure.CLIENT_SESSION_AUTHORITY_REJECTED
        GmailConnectionErrorCode.CLIENT_SESSION_UNAVAILABLE ->
            GmailConnectionFailure.CLIENT_SESSION_UNAVAILABLE
        GmailConnectionErrorCode.UNEXPECTED_RESPONSE ->
            GmailConnectionFailure.UNEXPECTED_RESPONSE
    }

    private fun ClientSessionErrorCode.invalidatesStoredCredential() = when (this) {
        ClientSessionErrorCode.CLIENT_SESSION_DEVICE_REJECTED,
        ClientSessionErrorCode.CLIENT_SESSION_TENANT_FORBIDDEN,
        ClientSessionErrorCode.CLIENT_SESSION_UNAUTHENTICATED,
        ClientSessionErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED -> true
        else -> false
    }

    private enum class SessionBootstrapOutcome {
        CONNECTED,
        INVALID,
        FAILURE,
    }

    private enum class SessionRefreshTrigger {
        NO_STORED_SESSION,
        DEVICE_BOOTSTRAP,
        EXPIRED,
        REJECTED,
    }
}

private val secureSessionLogger = Logger.getLogger("AndySecureSession")

private fun logSecureSessionEvent(event: String, fields: Map<String, String>) {
    val attributes = fields.entries
        .sortedBy { it.key }
        .joinToString(separator = " ") { (key, value) -> "$key=$value" }
    secureSessionLogger.info("event=$event $attributes".trimEnd())
}
