package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.core.humanidentity.HumanIdentityEngine
import io.github.escossio.andy.core.humanidentity.HumanIdentityFailure
import io.github.escossio.andy.core.humanidentity.HumanIdentityReference
import io.github.escossio.andy.core.humanidentity.HumanIdentityState
import io.github.escossio.andy.integrations.googleidentity.GoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.ProviderCredentialResult
import io.github.escossio.andy.sdk.clientapi.AuthenticatedBootstrapResult
import io.github.escossio.andy.sdk.clientapi.ChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionClient
import io.github.escossio.andy.sdk.clientapi.ClientSessionCompleteResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import io.github.escossio.andy.sdk.clientapi.ClientSessionErrorCode
import io.github.escossio.andy.sdk.clientapi.ContinuationResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapChallengeResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapClient
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapCompleteResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapErrorCode
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapRole
import io.github.escossio.andy.sdk.clientapi.HumanAuthClient
import io.github.escossio.andy.sdk.clientapi.HumanAuthContinuationGrant
import io.github.escossio.andy.sdk.clientapi.HumanAuthErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val devicePublicKeySpki: () -> String?,
    private val signDeviceChallenge: (String) -> String?,
    private val engine: HumanIdentityEngine = HumanIdentityEngine(ready),
) {
    private val mutable = MutableStateFlow(engine.state)
    val state: StateFlow<HumanIdentityState> = mutable.asStateFlow()

    private val bootstrapMutable =
        MutableStateFlow<DeviceBootstrapState>(DeviceBootstrapState.Idle)
    val bootstrapState: StateFlow<DeviceBootstrapState> = bootstrapMutable.asStateFlow()

    private val sessionMutable =
        MutableStateFlow<ClientSessionState>(ClientSessionState.Idle)
    val sessionState: StateFlow<ClientSessionState> = sessionMutable.asStateFlow()

    private var continuationGrant: HumanAuthContinuationGrant? = null
    private var validatedIdentity: HumanIdentityReference? = null
    private var clientSession: ClientSessionCredential? = null
    private val deviceReady = ready

    fun takeContinuationGrant(): HumanAuthContinuationGrant? {
        val grant = continuationGrant
        continuationGrant = null
        return grant
    }

    suspend fun continueWithGoogle() {
        continuationGrant = null
        validatedIdentity = null
        clientSession = null
        bootstrapMutable.value = DeviceBootstrapState.Idle
        sessionMutable.value = ClientSessionState.Idle

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
        clientSession = null
        sessionMutable.value = ClientSessionState.Idle
        continueClientSession()
    }

    suspend fun retryClientSession() {
        if (clientSession != null) {
            loadAuthenticatedBootstrap()
        } else {
            continueClientSession()
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

    fun restartAuthentication() {
        continuationGrant = null
        validatedIdentity = null
        clientSession = null
        bootstrapMutable.value = DeviceBootstrapState.Idle
        sessionMutable.value = ClientSessionState.Idle
        mutable.value = engine.retry()
    }

    fun retry() = restartAuthentication()

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
                // A successful completion consumes the continuation authority server-side.
                continuationGrant = null
                if (result.established.humanIdentityId != identity.opaqueId) {
                    failBootstrap(DeviceBootstrapFailure.HUMAN_IDENTITY_MISMATCH)
                } else {
                    bootstrapMutable.value =
                        DeviceBootstrapState.Established(result.established)
                    continueClientSession()
                }
            }
            is DeviceBootstrapCompleteResult.Failure ->
                failBootstrap(result.error.failure())
        }
    }

    private suspend fun continueClientSession() {
        if (!deviceReady) {
            return failSession(ClientSessionFailure.DEVICE_IDENTITY_UNAVAILABLE)
        }

        sessionMutable.value = ClientSessionState.Establishing
        val publicKey = try {
            devicePublicKeySpki()
        } catch (_: Exception) {
            null
        } ?: return failSession(ClientSessionFailure.DEVICE_IDENTITY_UNAVAILABLE)

        val requestedTenantId =
            (bootstrapMutable.value as? DeviceBootstrapState.Established)
                ?.authority
                ?.initialTenantId

        val challenge = when (
            val result = sessionClient.start(
                publicKeySpkiB64Url = publicKey,
                requestedTenantId = requestedTenantId,
            )
        ) {
            is ClientSessionChallengeResult.Success -> result.challenge
            is ClientSessionChallengeResult.Failure ->
                return failSession(result.error.failure())
        }

        val signature = try {
            signDeviceChallenge(challenge.challengeB64Url)
        } catch (_: Exception) {
            null
        } ?: return failSession(ClientSessionFailure.DEVICE_IDENTITY_UNAVAILABLE)

        val issued = when (
            val result = sessionClient.complete(
                challenge.challengeId,
                signature,
            )
        ) {
            is ClientSessionCompleteResult.Success -> result.session
            is ClientSessionCompleteResult.Failure ->
                return failSession(result.error.failure())
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
            clientSession = null
            return failSession(ClientSessionFailure.SESSION_BOOTSTRAP_MISMATCH)
        }

        clientSession = issued
        loadAuthenticatedBootstrap()
    }

    private suspend fun loadAuthenticatedBootstrap() {
        val session = clientSession
            ?: return failSession(ClientSessionFailure.CLIENT_SESSION_UNAUTHENTICATED)

        sessionMutable.value = ClientSessionState.LoadingBootstrap
        when (val result = sessionClient.bootstrap(session)) {
            is AuthenticatedBootstrapResult.Success -> {
                val bootstrap = result.bootstrap
                if (
                    bootstrap.humanIdentityId != session.humanIdentityId ||
                    bootstrap.activeTenantId != session.tenantId ||
                    bootstrap.device.deviceId != session.deviceId ||
                    bootstrap.sessionExpiresAt != session.expiresAt ||
                    bootstrap.memberships.none { it.tenantId == bootstrap.activeTenantId }
                ) {
                    clientSession = null
                    failSession(ClientSessionFailure.SESSION_BOOTSTRAP_MISMATCH)
                } else {
                    sessionMutable.value = ClientSessionState.Connected(bootstrap)
                }
            }
            is AuthenticatedBootstrapResult.Failure ->
                failSession(result.error.failure())
        }
    }

    private fun failSession(reason: ClientSessionFailure) {
        sessionMutable.value = ClientSessionState.Failure(reason)
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
}
