package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.core.humanidentity.HumanIdentityReference
import io.github.escossio.andy.core.humanidentity.HumanIdentityState
import io.github.escossio.andy.integrations.googleidentity.GoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.ProviderCredentialResult
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationAcquirer
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationResult
import io.github.escossio.andy.integrations.googleauthorization.GoogleServerAuthorizationCode
import io.github.escossio.andy.sdk.clientapi.AuthenticatedBootstrapResult
import io.github.escossio.andy.sdk.clientapi.AuthenticatedClientBootstrap
import io.github.escossio.andy.sdk.clientapi.ChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallenge
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionClient
import io.github.escossio.andy.sdk.clientapi.ClientSessionCompleteResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import io.github.escossio.andy.sdk.clientapi.ClientSessionErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientSessionStore
import io.github.escossio.andy.sdk.clientapi.ClientDevice
import io.github.escossio.andy.sdk.clientapi.ClientTenantMembership
import io.github.escossio.andy.sdk.clientapi.ClientTenantRole
import io.github.escossio.andy.sdk.clientapi.ContinuationResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapChallenge
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapChallengeResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapClient
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapCompleteResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapErrorCode
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapEstablished
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapRole
import io.github.escossio.andy.sdk.clientapi.GoogleChallenge
import io.github.escossio.andy.sdk.clientapi.HumanAuthClient
import io.github.escossio.andy.sdk.clientapi.HumanAuthContinuationGrant
import io.github.escossio.andy.sdk.clientapi.HumanAuthContinuationPurpose
import io.github.escossio.andy.sdk.clientapi.HumanAuthErrorCode
import io.github.escossio.andy.sdk.clientapi.HumanIdentityContinuation
import io.github.escossio.andy.sdk.clientapi.GMAIL_METADATA_SCOPE
import io.github.escossio.andy.sdk.clientapi.GMAIL_READONLY_SCOPE
import io.github.escossio.andy.sdk.clientapi.GmailConnection
import io.github.escossio.andy.sdk.clientapi.GmailConnectionClient
import io.github.escossio.andy.sdk.clientapi.GmailConnectionErrorCode
import io.github.escossio.andy.sdk.clientapi.GmailConnectionResult
import io.github.escossio.andy.sdk.clientapi.GmailConnectionStatus
import io.github.escossio.andy.sdk.clientapi.VerifyResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OnboardingCoordinatorTest {
    @Test
    fun notReadyDoesNotStartHumanOrDeviceBootstrap() = runBlocking {
        val human = FakeHumanClient()
        val bootstrap = FakeBootstrapClient()
        val coordinator = coordinator(false, human, bootstrap)

        coordinator.continueWithGoogle()

        assertEquals(0, human.challengeCalls)
        assertEquals(0, bootstrap.startCalls)
        assertEquals(HumanIdentityState.DeviceIdentityUnavailable, coordinator.state.value)
        assertSame(DeviceBootstrapState.Idle, coordinator.bootstrapState.value)
    }

    @Test
    fun validatedHumanContinuesThroughDeviceBootstrapAndClearsGrantAfterSuccess() = runBlocking {
        val grant = grant()
        val human = FakeHumanClient(
            continuation = ContinuationResult.Success(
                HumanIdentityContinuation(HumanIdentityReference(HUMAN_ID), grant),
            ),
        )
        val bootstrap = FakeBootstrapClient()
        val coordinator = coordinator(true, human, bootstrap)

        coordinator.continueWithGoogle()

        assertEquals(
            HumanIdentityState.Validated(HumanIdentityReference(HUMAN_ID)),
            coordinator.state.value,
        )
        assertTrue(coordinator.bootstrapState.value is DeviceBootstrapState.Established)
        val established =
            (coordinator.bootstrapState.value as DeviceBootstrapState.Established).authority
        assertEquals(HUMAN_ID, established.humanIdentityId)
        assertEquals("tnt_synthetic", established.initialTenantId)
        assertEquals(DEVICE_ID, established.device.deviceId)
        assertEquals(1, bootstrap.startCalls)
        assertEquals(1, bootstrap.completeCalls)
        assertNull(coordinator.takeContinuationGrant())
        assertFalse(coordinator.state.value.toString().contains(GRANT_TOKEN))
        assertFalse(coordinator.bootstrapState.value.toString().contains(GRANT_TOKEN))
    }

    @Test
    fun bootstrapFailureKeepsUnconsumedGrantOnlyInMemoryForRetry() = runBlocking {
        val grant = grant()
        val bootstrap = FakeBootstrapClient(
            start = DeviceBootstrapChallengeResult.Failure(
                DeviceBootstrapErrorCode.NETWORK_FAILURE,
            ),
        )
        val coordinator = coordinator(
            true,
            FakeHumanClient(
                continuation = ContinuationResult.Success(
                    HumanIdentityContinuation(HumanIdentityReference(HUMAN_ID), grant),
                ),
            ),
            bootstrap,
        )

        coordinator.continueWithGoogle()

        assertEquals(
            DeviceBootstrapState.Failure(DeviceBootstrapFailure.NETWORK_FAILURE),
            coordinator.bootstrapState.value,
        )
        assertSame(grant, coordinator.takeContinuationGrant())
        assertNull(coordinator.takeContinuationGrant())
    }

    @Test
    fun retryDeviceBootstrapDoesNotRepeatGoogleWhenGrantIsStillAvailable() = runBlocking {
        val bootstrap = SequencedBootstrapClient()
        val human = FakeHumanClient()
        val coordinator = coordinator(true, human, bootstrap)

        coordinator.continueWithGoogle()
        assertEquals(
            DeviceBootstrapState.Failure(DeviceBootstrapFailure.NETWORK_FAILURE),
            coordinator.bootstrapState.value,
        )
        assertEquals(1, human.challengeCalls)

        coordinator.retryDeviceBootstrap()

        assertTrue(coordinator.bootstrapState.value is DeviceBootstrapState.Established)
        assertEquals(1, human.challengeCalls)
        assertEquals(2, bootstrap.startCalls)
        assertNull(coordinator.takeContinuationGrant())
    }

    @Test
    fun successfulCompleteWithDifferentHumanFailsClosedAndClearsConsumedGrant() = runBlocking {
        val mismatched = established(humanId = "hid_" + "z".repeat(24))
        val bootstrap = FakeBootstrapClient(
            complete = DeviceBootstrapCompleteResult.Success(mismatched),
        )
        val coordinator = coordinator(true, FakeHumanClient(), bootstrap)

        coordinator.continueWithGoogle()

        assertEquals(
            DeviceBootstrapState.Failure(DeviceBootstrapFailure.HUMAN_IDENTITY_MISMATCH),
            coordinator.bootstrapState.value,
        )
        assertNull(coordinator.takeContinuationGrant())
    }

    @Test
    fun restartAuthenticationDiscardsUnconsumedGrantBootstrapAndStoredSession() = runBlocking {
        val bootstrap = FakeBootstrapClient(
            start = DeviceBootstrapChallengeResult.Failure(
                DeviceBootstrapErrorCode.NETWORK_FAILURE,
            ),
        )
        val store = FakeSessionStore(sessionCredential())
        val coordinator = coordinator(true, FakeHumanClient(), bootstrap, store = store)
        coordinator.continueWithGoogle()

        coordinator.restartAuthentication()

        assertNull(coordinator.takeContinuationGrant())
        assertEquals(HumanIdentityState.Unauthenticated, coordinator.state.value)
        assertSame(DeviceBootstrapState.Idle, coordinator.bootstrapState.value)
        assertNull(store.current)
        assertTrue(store.clearCalls >= 1)
    }

    @Test
    fun humanFailureNeverStartsBootstrap() = runBlocking {
        val bootstrap = FakeBootstrapClient()
        val coordinator = coordinator(
            true,
            FakeHumanClient(
                challenge = ChallengeResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE),
            ),
            bootstrap,
        )

        coordinator.continueWithGoogle()

        assertFalse(coordinator.state.value is HumanIdentityState.Validated)
        assertEquals(0, bootstrap.startCalls)
        assertNull(coordinator.takeContinuationGrant())
    }

    @Test
    fun existingEnrolledDeviceConnectsWithoutGoogleOrDeviceBootstrap() = runBlocking {
        val human = FakeHumanClient()
        val bootstrap = FakeBootstrapClient()
        val session = FakeSessionClient()
        val coordinator = coordinator(true, human, bootstrap, session)

        coordinator.continueWithExistingDevice()

        assertEquals(0, human.challengeCalls)
        assertEquals(0, bootstrap.startCalls)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)
        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals("tnt_synthetic", connected.bootstrap.activeTenantId)
        assertEquals(DEVICE_ID, connected.bootstrap.device.deviceId)
        assertFalse(connected.toString().contains(SESSION_TOKEN))
    }

    @Test
    fun successfulDeviceBootstrapContinuesIntoClientSessionAutomatically() = runBlocking {
        val session = FakeSessionClient()
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
        )

        coordinator.continueWithGoogle()

        assertTrue(coordinator.bootstrapState.value is DeviceBootstrapState.Established)
        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)
    }

    @Test
    fun authenticatedBootstrapRetryReusesSameInMemorySession() = runBlocking {
        val human = FakeHumanClient()
        val bootstrap = FakeBootstrapClient()
        val session = SequencedSessionClient()
        val coordinator = coordinator(true, human, bootstrap, session)

        coordinator.continueWithExistingDevice()

        assertEquals(
            ClientSessionState.Failure(ClientSessionFailure.NETWORK_FAILURE),
            coordinator.sessionState.value,
        )
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)

        coordinator.retryClientSession()

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(2, session.bootstrapCalls)
        assertFalse(coordinator.sessionState.value.toString().contains(SESSION_TOKEN))
    }

    @Test
    fun startupRestoresStoredSessionWithoutIssuingNewChallenge() = runBlocking {
        val store = FakeSessionStore(sessionCredential())
        val session = FakeSessionClient()
        val human = FakeHumanClient()
        val bootstrap = FakeBootstrapClient()
        val coordinator = coordinator(
            true,
            human,
            bootstrap,
            session,
            store,
        )

        coordinator.restoreClientSessionOnStartup()

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(0, session.startCalls)
        assertEquals(0, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)
        assertEquals(0, human.challengeCalls)
        assertEquals(0, bootstrap.startCalls)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun expiredStoredSessionIsPurgedThenFreshPossessionEstablishesOneSession() = runBlocking {
        val expired = sessionCredential(Instant.parse("2028-01-01T00:00:00Z"))
        val store = FakeSessionStore(expired)
        val session = FakeSessionClient()
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
            store,
            now = { Instant.parse("2029-01-01T00:00:00Z") },
        )

        coordinator.restoreClientSessionOnStartup()

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(1, store.clearCalls)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun rejectedStoredSessionIsPurgedThenFreshPossessionIsUsed() = runBlocking {
        val store = FakeSessionStore(sessionCredential())
        val session = RestoreRejectedThenFreshSessionClient()
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
            store,
        )

        coordinator.restoreClientSessionOnStartup()

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(1, store.clearCalls)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(2, session.bootstrapCalls)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun duplicateConnectActionsConvergeOnOneSessionEstablishmentFlight() = runBlocking {
        val session = DelayedSessionClient()
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
        )

        coroutineScope {
            awaitAll(
                async { coordinator.continueWithExistingDevice() },
                async { coordinator.continueWithExistingDevice() },
            )
        }

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)
    }

    @Test
    fun automaticStartupOnUnknownDeviceLeavesNormalOnboardingAvailable() = runBlocking {
        val session = FakeSessionClient(
            start = ClientSessionChallengeResult.Failure(
                ClientSessionErrorCode.CLIENT_SESSION_DEVICE_REJECTED,
            ),
        )
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
        )

        coordinator.restoreClientSessionOnStartup()

        assertSame(ClientSessionState.Idle, coordinator.sessionState.value)
        assertEquals(HumanIdentityState.Unauthenticated, coordinator.state.value)
        assertEquals(1, session.startCalls)
        assertEquals(0, session.completeCalls)
    }

    @Test
    fun gmailConnectUsesOneTimeServerCodeAfterClientSession() = runBlocking {
        val gmail = FakeGmailClient()
        val authorization = FakeGmailAuthorization()
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            gmailClient = gmail,
            gmailAuthorization = authorization,
        )

        coordinator.continueWithExistingDevice()
        coordinator.connectGmail()

        assertTrue(coordinator.gmailState.value is GmailConnectionState.Connected)
        assertEquals(listOf(false), authorization.forceConsentCalls)
        assertEquals(1, gmail.connectCodes.size)
        assertTrue(gmail.connectCodes.single().startsWith("code-"))
    }

    @Test
    fun gmailMissingRefreshTokenRetriesOnceWithExplicitConsent() = runBlocking {
        val gmail = FakeGmailClient(
            connectResults = ArrayDeque(
                listOf(
                    GmailConnectionResult.Failure(
                        GmailConnectionErrorCode.GMAIL_REFRESH_TOKEN_REQUIRED,
                    ),
                    connectedGmailResult(),
                ),
            ),
        )
        val authorization = FakeGmailAuthorization()
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            gmailClient = gmail,
            gmailAuthorization = authorization,
        )

        coordinator.continueWithExistingDevice()
        coordinator.connectGmail()

        assertEquals(listOf(false, true), authorization.forceConsentCalls)
        assertEquals(2, gmail.connectCodes.size)
        assertTrue(coordinator.gmailState.value is GmailConnectionState.Connected)
    }

    @Test
    fun gmailCancelledAuthorizationNeverCallsBackendConnect() = runBlocking {
        val gmail = FakeGmailClient()
        val authorization = FakeGmailAuthorization(
            results = ArrayDeque(
                listOf(GoogleAuthorizationResult.Cancelled),
            ),
        )
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            gmailClient = gmail,
            gmailAuthorization = authorization,
        )

        coordinator.continueWithExistingDevice()
        coordinator.connectGmail()

        assertEquals(emptyList<String>(), gmail.connectCodes)
        assertEquals(
            GmailConnectionState.Failure(
                GmailConnectionFailure.PROVIDER_CANCELLED,
            ),
            coordinator.gmailState.value,
        )
    }

    @Test
    fun gmailDisconnectUsesBackendOnly() = runBlocking {
        val gmail = FakeGmailClient()
        val authorization = FakeGmailAuthorization()
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            gmailClient = gmail,
            gmailAuthorization = authorization,
        )

        coordinator.continueWithExistingDevice()
        coordinator.disconnectGmail()

        assertEquals(1, gmail.disconnectCalls)
        assertTrue(authorization.forceConsentCalls.isEmpty())
        assertSame(GmailConnectionState.Disconnected, coordinator.gmailState.value)
    }

    private fun coordinator(
        ready: Boolean,
        client: HumanAuthClient,
        bootstrapClient: DeviceBootstrapClient,
        sessionClient: ClientSessionClient = FakeSessionClient(),
        store: ClientSessionStore = FakeSessionStore(),
        now: () -> Instant = { Instant.parse("2029-01-01T00:00:00Z") },
        gmailClient: GmailConnectionClient? = null,
        gmailAuthorization: GoogleAuthorizationAcquirer? = null,
    ) = OnboardingCoordinator(
        ready = ready,
        config = OnboardingConfiguration(
            "https://synthetic.invalid",
            "client-id-synthetic",
            "Synthetic Android",
        ),
        client = client,
        provider = object : GoogleCredentialAcquirer {
            override suspend fun acquire(nonce: String) =
                ProviderCredentialResult.Token("token-synthetic")
        },
        bootstrapClient = bootstrapClient,
        sessionClient = sessionClient,
        gmailClient = gmailClient,
        gmailAuthorization = gmailAuthorization,
        devicePublicKeySpki = { "A".repeat(120) },
        signDeviceChallenge = { "c".repeat(96) },
        sessionStore = store,
        now = now,
    )

    private class FakeGmailAuthorization(
        private val results: ArrayDeque<GoogleAuthorizationResult> = ArrayDeque(
            listOf(
                GoogleAuthorizationResult.Authorized(
                    GoogleServerAuthorizationCode("code-" + "a".repeat(16)),
                ),
                GoogleAuthorizationResult.Authorized(
                    GoogleServerAuthorizationCode("code-" + "b".repeat(16)),
                ),
            ),
        ),
    ) : GoogleAuthorizationAcquirer {
        val forceConsentCalls = mutableListOf<Boolean>()

        override suspend fun acquire(
            requestedScopes: Set<String>,
            forceConsent: Boolean,
        ): GoogleAuthorizationResult {
            assertEquals(setOf(GMAIL_READONLY_SCOPE), requestedScopes)
            forceConsentCalls += forceConsent
            return results.removeFirst()
        }
    }

    private class FakeGmailClient(
        private val connectResults: ArrayDeque<GmailConnectionResult> = ArrayDeque(
            listOf(connectedGmailResult()),
        ),
    ) : GmailConnectionClient {
        val connectCodes = mutableListOf<String>()
        var disconnectCalls = 0

        override suspend fun getGmailConnection(
            session: ClientSessionCredential,
        ) = GmailConnectionResult.Success(
            GmailConnection(
                GmailConnectionStatus.DISCONNECTED,
                emptySet(),
            ),
        )

        override suspend fun connectGmail(
            session: ClientSessionCredential,
            authorizationCode: String,
        ): GmailConnectionResult {
            connectCodes += authorizationCode
            return connectResults.removeFirst()
        }

        override suspend fun disconnectGmail(
            session: ClientSessionCredential,
        ): GmailConnectionResult {
            disconnectCalls++
            return GmailConnectionResult.Success(
                GmailConnection(
                    GmailConnectionStatus.DISCONNECTED,
                    emptySet(),
                ),
            )
        }
    }

    private class FakeHumanClient(
        private val challenge: ChallengeResult = ChallengeResult.Success(
            GoogleChallenge(
                "hac_examplechallenge123456789",
                "n".repeat(32),
                "2030-01-01T00:00:00Z",
            ),
        ),
        private val continuation: ContinuationResult = ContinuationResult.Success(
            HumanIdentityContinuation(HumanIdentityReference(HUMAN_ID), grant()),
        ),
    ) : HumanAuthClient {
        var challengeCalls = 0

        override suspend fun requestChallenge(): ChallengeResult {
            challengeCalls++
            return challenge
        }

        override suspend fun verify(challengeId: String, idToken: String) =
            VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE)

        override suspend fun verifyAndContinue(challengeId: String, idToken: String) =
            continuation
    }

    private open class FakeBootstrapClient(
        private val start: DeviceBootstrapChallengeResult =
            DeviceBootstrapChallengeResult.Success(challenge()),
        private val complete: DeviceBootstrapCompleteResult =
            DeviceBootstrapCompleteResult.Success(established()),
    ) : DeviceBootstrapClient {
        var startCalls = 0
        var completeCalls = 0

        override suspend fun start(
            grant: HumanAuthContinuationGrant,
            publicKeySpkiB64Url: String,
            canonicalDeviceName: String,
            roles: Set<DeviceBootstrapRole>,
        ): DeviceBootstrapChallengeResult {
            startCalls++
            return start
        }

        override suspend fun complete(
            challengeId: String,
            signatureB64Url: String,
        ): DeviceBootstrapCompleteResult {
            completeCalls++
            return complete
        }
    }

    private class SequencedBootstrapClient : DeviceBootstrapClient {
        var startCalls = 0

        override suspend fun start(
            grant: HumanAuthContinuationGrant,
            publicKeySpkiB64Url: String,
            canonicalDeviceName: String,
            roles: Set<DeviceBootstrapRole>,
        ): DeviceBootstrapChallengeResult {
            startCalls++
            return if (startCalls == 1) {
                DeviceBootstrapChallengeResult.Failure(
                    DeviceBootstrapErrorCode.NETWORK_FAILURE,
                )
            } else {
                DeviceBootstrapChallengeResult.Success(challenge())
            }
        }

        override suspend fun complete(
            challengeId: String,
            signatureB64Url: String,
        ) = DeviceBootstrapCompleteResult.Success(established())
    }

    private open class FakeSessionClient(
        private val start: ClientSessionChallengeResult =
            ClientSessionChallengeResult.Success(sessionChallenge()),
        private val complete: ClientSessionCompleteResult =
            ClientSessionCompleteResult.Success(sessionCredential()),
        private val bootstrap: AuthenticatedBootstrapResult =
            AuthenticatedBootstrapResult.Success(authenticatedBootstrap()),
    ) : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            return start
        }

        override suspend fun complete(
            challengeId: String,
            signatureB64Url: String,
        ): ClientSessionCompleteResult {
            completeCalls++
            return complete
        }

        override suspend fun bootstrap(
            session: ClientSessionCredential,
        ): AuthenticatedBootstrapResult {
            bootstrapCalls++
            return bootstrap
        }
    }

    private class SequencedSessionClient : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            return ClientSessionChallengeResult.Success(sessionChallenge())
        }

        override suspend fun complete(
            challengeId: String,
            signatureB64Url: String,
        ): ClientSessionCompleteResult {
            completeCalls++
            return ClientSessionCompleteResult.Success(sessionCredential())
        }

        override suspend fun bootstrap(
            session: ClientSessionCredential,
        ): AuthenticatedBootstrapResult {
            bootstrapCalls++
            return if (bootstrapCalls == 1) {
                AuthenticatedBootstrapResult.Failure(ClientSessionErrorCode.NETWORK_FAILURE)
            } else {
                AuthenticatedBootstrapResult.Success(authenticatedBootstrap())
            }
        }
    }

    private class RestoreRejectedThenFreshSessionClient : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            return ClientSessionChallengeResult.Success(sessionChallenge())
        }

        override suspend fun complete(
            challengeId: String,
            signatureB64Url: String,
        ): ClientSessionCompleteResult {
            completeCalls++
            return ClientSessionCompleteResult.Success(sessionCredential())
        }

        override suspend fun bootstrap(
            session: ClientSessionCredential,
        ): AuthenticatedBootstrapResult {
            bootstrapCalls++
            return if (bootstrapCalls == 1) {
                AuthenticatedBootstrapResult.Failure(
                    ClientSessionErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED,
                )
            } else {
                AuthenticatedBootstrapResult.Success(authenticatedBootstrap())
            }
        }
    }

    private class DelayedSessionClient : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            delay(50)
            return ClientSessionChallengeResult.Success(sessionChallenge())
        }

        override suspend fun complete(
            challengeId: String,
            signatureB64Url: String,
        ): ClientSessionCompleteResult {
            completeCalls++
            return ClientSessionCompleteResult.Success(sessionCredential())
        }

        override suspend fun bootstrap(
            session: ClientSessionCredential,
        ): AuthenticatedBootstrapResult {
            bootstrapCalls++
            return AuthenticatedBootstrapResult.Success(authenticatedBootstrap())
        }
    }

    private class FakeSessionStore(
        var current: ClientSessionCredential? = null,
    ) : ClientSessionStore {
        var loadCalls = 0
        var saveCalls = 0
        var clearCalls = 0

        override suspend fun load(): ClientSessionCredential? {
            loadCalls++
            return current
        }

        override suspend fun save(session: ClientSessionCredential) {
            saveCalls++
            current = session
        }

        override suspend fun clear() {
            clearCalls++
            current = null
        }
    }

    private companion object {
        const val HUMAN_ID = "hid_exampleopaqueidentity123"
        const val GRANT_TOKEN = "hcg_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SESSION_TOKEN = "cst_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DEVICE_ID = "cdev_exampledevice12345678901"

        fun connectedGmailResult() = GmailConnectionResult.Success(
            GmailConnection(
                GmailConnectionStatus.CONNECTED,
                setOf(GMAIL_METADATA_SCOPE),
            ),
        )

        fun grant() = HumanAuthContinuationGrant(
            GRANT_TOKEN,
            HumanAuthContinuationPurpose.DEVICE_BOOTSTRAP,
            Instant.parse("2030-01-01T00:05:00Z"),
        )

        fun challenge() = DeviceBootstrapChallenge(
            "dbc_examplechallenge123456789",
            "b".repeat(43),
            Instant.parse("2030-01-01T00:06:00Z"),
        )

        fun sessionChallenge() = ClientSessionChallenge(
            "csc_examplechallenge123456789",
            "s".repeat(43),
            Instant.parse("2030-01-01T00:07:00Z"),
        )

        fun sessionCredential(
            expiresAt: Instant = Instant.parse("2030-01-01T00:15:00Z"),
        ) = ClientSessionCredential(
            token = SESSION_TOKEN,
            sessionId = "csn_examplesession12345678901",
            expiresAt = expiresAt,
            humanIdentityId = HUMAN_ID,
            deviceId = DEVICE_ID,
            tenantId = "tnt_synthetic",
        )

        fun authenticatedBootstrap() = AuthenticatedClientBootstrap(
            humanIdentityId = HUMAN_ID,
            activeTenantId = "tnt_synthetic",
            memberships = listOf(
                ClientTenantMembership(
                    "ctm_synthetic",
                    "tnt_synthetic",
                    ClientTenantRole.OWNER,
                ),
            ),
            device = ClientDevice(
                DEVICE_ID,
                "sha256:" + "f".repeat(64),
                "Synthetic Android",
                setOf(DeviceBootstrapRole.CLIENT, DeviceBootstrapRole.CAPABILITY_NODE),
            ),
            sessionExpiresAt = Instant.parse("2030-01-01T00:15:00Z"),
            serverTime = Instant.parse("2030-01-01T00:08:00Z"),
        )

        fun established(humanId: String = HUMAN_ID) = DeviceBootstrapEstablished(
            humanIdentityId = humanId,
            memberships = listOf(
                ClientTenantMembership(
                    "ctm_synthetic",
                    "tnt_synthetic",
                    ClientTenantRole.OWNER,
                ),
            ),
            initialTenantId = "tnt_synthetic",
            device = ClientDevice(
                DEVICE_ID,
                "sha256:" + "f".repeat(64),
                "Synthetic Android",
                setOf(DeviceBootstrapRole.CLIENT, DeviceBootstrapRole.CAPABILITY_NODE),
            ),
        )
    }
}
