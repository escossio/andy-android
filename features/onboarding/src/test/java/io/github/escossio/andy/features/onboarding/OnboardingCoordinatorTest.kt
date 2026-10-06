package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.core.humanidentity.HumanIdentityReference
import io.github.escossio.andy.core.humanidentity.HumanIdentityState
import io.github.escossio.andy.integrations.googleidentity.GoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.ProviderCredentialResult
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationAcquirer
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationResult
import io.github.escossio.andy.integrations.googleauthorization.GoogleServerAuthorizationCode
import io.github.escossio.andy.sdk.clientapi.AttentionRouterHumanAuthClient
import io.github.escossio.andy.sdk.clientapi.HumanAuthTransport
import io.github.escossio.andy.sdk.clientapi.TransportResponse
import io.github.escossio.andy.sdk.clientapi.AuthenticatedBootstrapResult
import io.github.escossio.andy.sdk.clientapi.AuthenticatedClientBootstrap
import io.github.escossio.andy.sdk.clientapi.AuthenticatedClientTenantDirectory
import io.github.escossio.andy.sdk.clientapi.AuthenticatedTenantDirectoryResult
import io.github.escossio.andy.sdk.clientapi.ChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallenge
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionClient
import io.github.escossio.andy.sdk.clientapi.ClientSessionCompleteResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import io.github.escossio.andy.sdk.clientapi.ClientSessionErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientSessionStore
import io.github.escossio.andy.sdk.clientapi.ClientDevice
import io.github.escossio.andy.sdk.clientapi.ClientTenantDirectoryMembership
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
    fun multiTenantBootstrapWaitsForExplicitTenantBeforeIssuingSession() = runBlocking {
        val memberships = dualTenantMemberships()
        val bootstrap = FakeBootstrapClient(
            complete = DeviceBootstrapCompleteResult.Success(
                established(
                    memberships = memberships,
                    initialTenantId = null,
                ),
            ),
        )
        val session = TenantSwitchSessionClient(memberships = memberships)
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            bootstrap,
            session,
        )

        coordinator.continueWithGoogle()

        assertEquals(
            ClientSessionState.AwaitingTenantSelection(memberships),
            coordinator.sessionState.value,
        )
        assertEquals(0, session.startCalls)
        assertEquals(0, session.completeCalls)

        assertTrue(coordinator.selectTenantForSession(TARGET_TENANT))

        assertEquals(listOf(TARGET_TENANT), session.requestedTenantIds)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(TARGET_TENANT, connected.bootstrap.activeTenantId)
    }

    @Test
    fun googleReauthenticationPreservesPreviousTenantWhenStillAuthorized() = runBlocking {
        val memberships = dualTenantMemberships()
        val store = FakeSessionStore(sessionCredential(tenantId = TARGET_TENANT))
        val bootstrap = FakeBootstrapClient(
            complete = DeviceBootstrapCompleteResult.Success(
                established(
                    memberships = memberships,
                    initialTenantId = null,
                ),
            ),
        )
        val session = TenantSwitchSessionClient(memberships = memberships)
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            bootstrap,
            session,
            store,
        )

        coordinator.restoreClientSessionOnStartup()
        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)

        coordinator.continueWithGoogle()

        assertEquals(listOf(TARGET_TENANT), session.requestedTenantIds)
        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(TARGET_TENANT, connected.bootstrap.activeTenantId)
        assertEquals(TARGET_TENANT, store.current?.tenantId)
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
    fun foregroundMaintenanceDoesNotRenewFreshSession() = runBlocking {
        val currentTime = Instant.parse("2029-01-01T00:00:00Z")
        val stored = sessionCredential(currentTime.plusSeconds(300))
        val store = FakeSessionStore(stored)
        val session = MaintenanceSessionClient()
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
            store,
            now = { currentTime },
        )

        coordinator.restoreClientSessionOnStartup()
        coordinator.maintainClientSession()

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(0, session.startCalls)
        assertEquals(0, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)
        assertSame(stored, store.current)
    }

    @Test
    fun foregroundMaintenanceRenewsBeforeSessionExpiresAndPreservesTenant() = runBlocking {
        val currentTime = Instant.parse("2029-01-01T00:00:00Z")
        val nearlyExpired = sessionCredential(currentTime.plusSeconds(45))
        val store = FakeSessionStore(nearlyExpired)
        val session = MaintenanceSessionClient()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
            store,
            now = { currentTime },
            sessionTelemetry = { event, fields -> events += event to fields },
        )

        coordinator.restoreClientSessionOnStartup()
        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(0, session.startCalls)
        assertEquals(1, session.bootstrapCalls)

        coordinator.maintainClientSession()

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(1, session.startCalls)
        assertEquals(listOf("tnt_synthetic"), session.requestedTenantIds)
        assertEquals(1, session.completeCalls)
        assertEquals(2, session.bootstrapCalls)
        assertEquals(2, store.saveCalls)
        assertNotSame(nearlyExpired, store.current)
        assertEquals(Instant.parse("2030-01-01T00:15:00Z"), store.current?.expiresAt)
        assertTrue(
            events.any {
                it.first == "CLIENT_SESSION_VALIDATION" &&
                    it.second["result"] == "EXPIRING"
            },
        )
        assertTrue(
            events.any {
                it.first == "CLIENT_SESSION_REFRESH_START" &&
                    it.second["trigger"] == "EXPIRING"
            },
        )
    }

    @Test
    fun expiredStoredSessionIsReplacedOnlyAfterFreshPossessionSucceeds() = runBlocking {
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
        assertEquals(0, store.clearCalls)
        assertEquals(1, session.startCalls)
        assertEquals(listOf("tnt_synthetic"), session.requestedTenantIds)
        assertEquals(1, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)
        assertEquals(1, store.saveCalls)
        assertNotSame(expired, store.current)
        assertEquals(Instant.parse("2030-01-01T00:15:00Z"), store.current?.expiresAt)
    }

    @Test
    fun rejectedStoredSessionIsReplacedOnlyAfterFreshPossessionSucceeds() = runBlocking {
        val stored = sessionCredential()
        val store = FakeSessionStore(stored)
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
        assertEquals(0, store.clearCalls)
        assertEquals(1, session.startCalls)
        assertEquals(listOf("tnt_synthetic"), session.requestedTenantIds)
        assertEquals(1, session.completeCalls)
        assertEquals(2, session.bootstrapCalls)
        assertEquals(1, store.saveCalls)
        assertNotSame(stored, store.current)
        assertEquals(Instant.parse("2030-01-01T00:15:00Z"), store.current?.expiresAt)
    }

    @Test
    fun expiredRenewalTransientStartFailureThenManualRetryPreservesTenant() = runBlocking {
        val expired = sessionCredential(Instant.parse("2028-01-01T00:00:00Z"))
        val store = FakeSessionStore(expired)
        val session = TransientStartThenSuccessSessionClient()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
            store,
            now = { Instant.parse("2029-01-01T00:00:00Z") },
            sessionTelemetry = { event, fields -> events += event to fields },
        )

        coordinator.restoreClientSessionOnStartup()

        assertEquals(
            ClientSessionState.Failure(ClientSessionFailure.NETWORK_FAILURE),
            coordinator.sessionState.value,
        )
        assertSame(expired, store.current)
        assertEquals(0, store.clearCalls)
        assertEquals(0, store.saveCalls)

        coordinator.retryClientSession()

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(listOf("tnt_synthetic", "tnt_synthetic"), session.requestedTenantIds)
        assertEquals(2, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(1, session.bootstrapCalls)
        assertEquals(1, store.saveCalls)
        assertNotSame(expired, store.current)
        assertEquals(Instant.parse("2030-01-01T00:15:00Z"), store.current?.expiresAt)
        assertTrue(events.any { it.first == "SECURE_RETRY_CLICK" })
        assertTrue(events.any { it.first == "CLIENT_SESSION_REFRESH_START" })
        assertTrue(events.any { it.first == "CLIENT_SESSION_ACCEPTED" })
        val renderedEvents = events.toString()
        listOf(
            SESSION_TOKEN,
            HUMAN_ID,
            DEVICE_ID,
            "tnt_synthetic",
            "A".repeat(120),
            "c".repeat(96),
        ).forEach { sensitive -> assertFalse(renderedEvents.contains(sensitive)) }
    }

    @Test
    fun replacementBootstrapFailureKeepsOldStoreUntilAccepted() = runBlocking {
        val expired = sessionCredential(Instant.parse("2028-01-01T00:00:00Z"))
        val store = FakeSessionStore(expired)
        val session = SequencedSessionClient()
        val coordinator = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
            store,
            now = { Instant.parse("2029-01-01T00:00:00Z") },
        )

        coordinator.restoreClientSessionOnStartup()

        assertEquals(
            ClientSessionState.Failure(ClientSessionFailure.NETWORK_FAILURE),
            coordinator.sessionState.value,
        )
        assertSame(expired, store.current)
        assertEquals(0, store.clearCalls)
        assertEquals(0, store.saveCalls)

        coordinator.retryClientSession()

        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(2, session.bootstrapCalls)
        assertEquals(1, store.saveCalls)
        assertNotSame(expired, store.current)
        assertEquals(Instant.parse("2030-01-01T00:15:00Z"), store.current?.expiresAt)
    }

    @Test
    fun expiredRenewalFailureSurvivesCoordinatorRestart() = runBlocking {
        val expired = sessionCredential(Instant.parse("2028-01-01T00:00:00Z"))
        val store = FakeSessionStore(expired)
        val session = TransientStartThenSuccessSessionClient()

        coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
            store,
            now = { Instant.parse("2029-01-01T00:00:00Z") },
        ).restoreClientSessionOnStartup()

        assertSame(expired, store.current)
        assertEquals(0, store.clearCalls)

        val restarted = coordinator(
            true,
            FakeHumanClient(),
            FakeBootstrapClient(),
            session,
            store,
            now = { Instant.parse("2029-01-01T00:00:00Z") },
        )
        restarted.restoreClientSessionOnStartup()

        assertTrue(restarted.sessionState.value is ClientSessionState.Connected)
        assertEquals(listOf("tnt_synthetic", "tnt_synthetic"), session.requestedTenantIds)
        assertEquals(0, store.clearCalls)
        assertEquals(1, store.saveCalls)
        assertNotSame(expired, store.current)
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
    fun tenantDirectoryPublishesOnlyExactAuthenticatedMemberships() = runBlocking {
        val memberships = dualTenantMemberships()
        val session = FakeSessionClient(
            bootstrap = AuthenticatedBootstrapResult.Success(
                authenticatedBootstrap(
                    activeTenantId = SOURCE_TENANT,
                    memberships = memberships,
                ),
            ),
            directory = AuthenticatedTenantDirectoryResult.Success(
                AuthenticatedClientTenantDirectory(
                    activeTenantId = SOURCE_TENANT,
                    memberships = listOf(
                        ClientTenantDirectoryMembership(
                            membershipId = "ctm_source",
                            tenantId = SOURCE_TENANT,
                            displayName = "Personal",
                            role = ClientTenantRole.OWNER,
                        ),
                        ClientTenantDirectoryMembership(
                            membershipId = "ctm_target",
                            tenantId = TARGET_TENANT,
                            displayName = "Leonardo",
                            role = ClientTenantRole.OWNER,
                        ),
                    ),
                ),
            ),
        )
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = FakeSessionStore(
                sessionCredential(tenantId = SOURCE_TENANT),
            ),
        )

        coordinator.restoreClientSessionOnStartup()
        coordinator.refreshTenantDirectory()

        val directory =
            coordinator.tenantDirectoryState.value as TenantDirectoryState.Available
        assertEquals(SOURCE_TENANT, directory.activeTenantId)
        assertEquals(listOf("Personal", "Leonardo"), directory.memberships.map { it.displayName })
        assertEquals(1, session.directoryCalls)
    }

    @Test
    fun tenantDirectoryAuthorityMismatchDoesNotInvalidateConnectedSession() = runBlocking {
        val memberships = dualTenantMemberships()
        val session = FakeSessionClient(
            bootstrap = AuthenticatedBootstrapResult.Success(
                authenticatedBootstrap(
                    activeTenantId = SOURCE_TENANT,
                    memberships = memberships,
                ),
            ),
            directory = AuthenticatedTenantDirectoryResult.Success(
                AuthenticatedClientTenantDirectory(
                    activeTenantId = SOURCE_TENANT,
                    memberships = listOf(
                        ClientTenantDirectoryMembership(
                            membershipId = "ctm_source",
                            tenantId = SOURCE_TENANT,
                            displayName = "Personal",
                            role = ClientTenantRole.OWNER,
                        ),
                        ClientTenantDirectoryMembership(
                            membershipId = "ctm_wrong",
                            tenantId = TARGET_TENANT,
                            displayName = "Leonardo",
                            role = ClientTenantRole.OWNER,
                        ),
                    ),
                ),
            ),
        )
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = FakeSessionStore(
                sessionCredential(tenantId = SOURCE_TENANT),
            ),
        )

        coordinator.restoreClientSessionOnStartup()
        coordinator.refreshTenantDirectory()

        assertSame(
            TenantDirectoryState.Unavailable,
            coordinator.tenantDirectoryState.value,
        )
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(SOURCE_TENANT, connected.bootstrap.activeTenantId)
    }

    @Test
    fun explicitTenantSwitchUsesFreshDeviceSessionAndResetsGmailState() = runBlocking {
        val memberships = dualTenantMemberships()
        val store = FakeSessionStore(
            sessionCredential(tenantId = SOURCE_TENANT),
        )
        val session = TenantSwitchSessionClient(memberships = memberships)
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = store,
            gmailClient = FakeGmailClient(),
            sessionTelemetry = { event, fields -> events += event to fields },
        )

        coordinator.restoreClientSessionOnStartup()
        coordinator.refreshGmailConnection()
        assertSame(GmailConnectionState.Disconnected, coordinator.gmailState.value)

        assertTrue(coordinator.switchActiveTenant(TARGET_TENANT))

        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(TARGET_TENANT, connected.bootstrap.activeTenantId)
        assertEquals(TARGET_TENANT, store.current?.tenantId)
        assertEquals(listOf(TARGET_TENANT), session.requestedTenantIds)
        assertEquals(1, session.startCalls)
        assertEquals(1, session.completeCalls)
        assertEquals(2, session.bootstrapCalls)
        assertSame(GmailConnectionState.Idle, coordinator.gmailState.value)
        assertTrue(
            events.any {
                it.first == "CLIENT_SESSION_TENANT_SWITCH" &&
                    it.second["result"] == "SUCCESS"
            },
        )
        val rendered = events.toString()
        assertFalse(rendered.contains(SOURCE_TENANT))
        assertFalse(rendered.contains(TARGET_TENANT))
    }

    @Test
    fun explicitTenantSwitchToCurrentTenantIsNoopAndPreservesGmailState() = runBlocking {
        val memberships = dualTenantMemberships()
        val store = FakeSessionStore(
            sessionCredential(tenantId = SOURCE_TENANT),
        )
        val session = TenantSwitchSessionClient(memberships = memberships)
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = store,
            gmailClient = FakeGmailClient(),
        )

        coordinator.restoreClientSessionOnStartup()
        coordinator.refreshGmailConnection()

        assertTrue(coordinator.switchActiveTenant(SOURCE_TENANT))

        assertEquals(0, session.startCalls)
        assertEquals(0, session.completeCalls)
        assertEquals(SOURCE_TENANT, store.current?.tenantId)
        assertSame(GmailConnectionState.Disconnected, coordinator.gmailState.value)
    }

    @Test
    fun explicitTenantSwitchRejectsTargetOutsideAuthenticatedMemberships() = runBlocking {
        val memberships = listOf(
            ClientTenantMembership(
                "ctm_source",
                SOURCE_TENANT,
                ClientTenantRole.OWNER,
            ),
        )
        val original = sessionCredential(tenantId = SOURCE_TENANT)
        val store = FakeSessionStore(original)
        val session = TenantSwitchSessionClient(memberships = memberships)
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = store,
            gmailClient = FakeGmailClient(),
        )

        coordinator.restoreClientSessionOnStartup()
        coordinator.refreshGmailConnection()

        assertFalse(coordinator.switchActiveTenant(TARGET_TENANT))

        assertEquals(0, session.startCalls)
        assertEquals(0, session.completeCalls)
        assertSame(original, store.current)
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(SOURCE_TENANT, connected.bootstrap.activeTenantId)
        assertSame(GmailConnectionState.Disconnected, coordinator.gmailState.value)
    }

    @Test
    fun explicitTenantSwitchFailureRollsBackSessionStoreStateAndGmail() = runBlocking {
        val memberships = dualTenantMemberships()
        val original = sessionCredential(tenantId = SOURCE_TENANT)
        val store = FakeSessionStore(original)
        val session = TenantSwitchSessionClient(
            memberships = memberships,
            startFailure = ClientSessionErrorCode.NETWORK_FAILURE,
        )
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = store,
            gmailClient = FakeGmailClient(),
            sessionTelemetry = { event, fields -> events += event to fields },
        )

        coordinator.restoreClientSessionOnStartup()
        coordinator.refreshGmailConnection()
        val savesBeforeSwitch = store.saveCalls

        assertFalse(coordinator.switchActiveTenant(TARGET_TENANT))

        assertSame(original, store.current)
        assertEquals(savesBeforeSwitch, store.saveCalls)
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(SOURCE_TENANT, connected.bootstrap.activeTenantId)
        assertSame(GmailConnectionState.Disconnected, coordinator.gmailState.value)
        assertTrue(
            events.any {
                it.first == "CLIENT_SESSION_TENANT_SWITCH" &&
                    it.second["result"] == "ROLLED_BACK"
            },
        )
    }

    @Test
    fun explicitTenantSwitchRejectsServerSessionForDifferentTenant() = runBlocking {
        val memberships = dualTenantMemberships()
        val original = sessionCredential(tenantId = SOURCE_TENANT)
        val store = FakeSessionStore(original)
        val session = TenantSwitchSessionClient(
            memberships = memberships,
            issuedTenantOverride = SOURCE_TENANT,
        )
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = store,
        )

        coordinator.restoreClientSessionOnStartup()
        val savesBeforeSwitch = store.saveCalls

        assertFalse(coordinator.switchActiveTenant(TARGET_TENANT))

        assertSame(original, store.current)
        assertEquals(savesBeforeSwitch, store.saveCalls)
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(SOURCE_TENANT, connected.bootstrap.activeTenantId)
    }

    @Test
    fun explicitTenantSwitchRejectsServerSessionForDifferentHumanBeforePersistence() = runBlocking {
        val memberships = dualTenantMemberships()
        val original = sessionCredential(tenantId = SOURCE_TENANT)
        val store = FakeSessionStore(original)
        val session = TenantSwitchSessionClient(
            memberships = memberships,
            issuedHumanIdentityOverride = OTHER_HUMAN_ID,
        )
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = store,
        )

        coordinator.restoreClientSessionOnStartup()
        val savesBeforeSwitch = store.saveCalls
        val bootstrapsBeforeSwitch = session.bootstrapCalls

        assertFalse(coordinator.switchActiveTenant(TARGET_TENANT))

        assertSame(original, store.current)
        assertEquals(savesBeforeSwitch, store.saveCalls)
        assertEquals(bootstrapsBeforeSwitch, session.bootstrapCalls)
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(SOURCE_TENANT, connected.bootstrap.activeTenantId)
    }

    @Test
    fun explicitTenantSwitchRejectsServerSessionForDifferentDeviceBeforePersistence() = runBlocking {
        val memberships = dualTenantMemberships()
        val original = sessionCredential(tenantId = SOURCE_TENANT)
        val store = FakeSessionStore(original)
        val session = TenantSwitchSessionClient(
            memberships = memberships,
            issuedDeviceIdOverride = OTHER_DEVICE_ID,
        )
        val coordinator = coordinator(
            ready = true,
            client = FakeHumanClient(),
            bootstrapClient = FakeBootstrapClient(),
            sessionClient = session,
            store = store,
        )

        coordinator.restoreClientSessionOnStartup()
        val savesBeforeSwitch = store.saveCalls
        val bootstrapsBeforeSwitch = session.bootstrapCalls

        assertFalse(coordinator.switchActiveTenant(TARGET_TENANT))

        assertSame(original, store.current)
        assertEquals(savesBeforeSwitch, store.saveCalls)
        assertEquals(bootstrapsBeforeSwitch, session.bootstrapCalls)
        val connected = coordinator.sessionState.value as ClientSessionState.Connected
        assertEquals(SOURCE_TENANT, connected.bootstrap.activeTenantId)
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

    @Test
    fun providerResumeMaintenanceCannotRaceGoogleVerifyOrDeviceBootstrap() = runBlocking {
        val providerGate = SuspensionGate()
        val verifyGate = SuspensionGate()
        val bootstrapGate = SuspensionGate()
        val completeGate = SuspensionGate()
        val events = mutableListOf<String>()
        val human = object : FakeHumanClient() {
            override suspend fun verifyAndContinue(challengeId: String, idToken: String): ContinuationResult {
                verifyGate.pause()
                return super.verifyAndContinue(challengeId, idToken)
            }
        }
        val memberships = dualTenantMemberships()
        val bootstrap = object : FakeBootstrapClient(
            complete = DeviceBootstrapCompleteResult.Success(established(memberships = memberships)),
        ) {
            override suspend fun start(
                grant: HumanAuthContinuationGrant,
                publicKeySpkiB64Url: String,
                canonicalDeviceName: String,
                roles: Set<DeviceBootstrapRole>,
            ): DeviceBootstrapChallengeResult {
                bootstrapGate.pause()
                return super.start(grant, publicKeySpkiB64Url, canonicalDeviceName, roles)
            }
            override suspend fun complete(challengeId: String, signatureB64Url: String): DeviceBootstrapCompleteResult {
                completeGate.pause()
                return super.complete(challengeId, signatureB64Url)
            }
        }
        val session = TenantSwitchSessionClient(memberships = memberships)
        val coordinator = coordinator(
            true, human, bootstrap, session,
            provider = object : GoogleCredentialAcquirer {
                override suspend fun acquire(nonce: String): ProviderCredentialResult {
                    providerGate.pause()
                    return ProviderCredentialResult.Token("token-synthetic")
                }
            },
            sessionTelemetry = { event, _ -> events += event },
        )
        val auth = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.continueWithGoogle() }

        for (gate in listOf(providerGate, verifyGate, bootstrapGate, completeGate)) {
            gate.entered.await()
            repeat(3) {
                coordinator.maintainClientSession()
                coordinator.restoreClientSessionOnStartup()
                coordinator.retryClientSession()
                coordinator.continueWithExistingDevice()
            }
            assertEquals(0, session.startCalls)
            assertSame(ClientSessionState.Idle, coordinator.sessionState.value)
            gate.release.complete(Unit)
        }
        auth.join()

        assertEquals(1, bootstrap.startCalls)
        assertEquals(1, bootstrap.completeCalls)
        assertNull(coordinator.takeContinuationGrant())
        assertEquals(ClientSessionState.AwaitingTenantSelection(memberships), coordinator.sessionState.value)
        repeat(5) {
            coordinator.maintainClientSession()
            coordinator.restoreClientSessionOnStartup()
            coordinator.retryClientSession()
            coordinator.continueWithExistingDevice()
        }
        assertEquals(0, session.startCalls)
        assertFalse(coordinator.selectTenantForSession("tnt_not_authorized"))
        assertTrue(coordinator.selectTenantForSession(TARGET_TENANT))
        assertEquals(listOf(TARGET_TENANT), session.requestedTenantIds)
        assertEquals(1, session.startCalls)
        repeat(5) { coordinator.maintainClientSession() }
        assertEquals(1, session.startCalls)
        val ordered = listOf("HUMAN_AUTH_START", "HUMAN_AUTH_PROVIDER_RETURNED", "HUMAN_AUTH_BACKEND_VALIDATED",
            "DEVICE_BOOTSTRAP_START", "DEVICE_BOOTSTRAP_ACCEPTED", "TENANT_SELECTION_REQUIRED", "CLIENT_SESSION_START")
        assertEquals(ordered, events.filter { it in ordered })
        assertTrue(events.contains("SESSION_MAINTENANCE_SKIPPED"))
    }

    @Test
    fun concurrentManualGoogleAndBootstrapRetriesDoNotCreateParallelFlows() = runBlocking {
        val providerGate = SuspensionGate()
        val bootstrapGate = SuspensionGate()
        val human = FakeHumanClient()
        val bootstrap = object : FakeBootstrapClient() {
            override suspend fun start(
                grant: HumanAuthContinuationGrant,
                publicKeySpkiB64Url: String,
                canonicalDeviceName: String,
                roles: Set<DeviceBootstrapRole>,
            ): DeviceBootstrapChallengeResult {
                bootstrapGate.pause()
                return super.start(grant, publicKeySpkiB64Url, canonicalDeviceName, roles)
            }
        }
        val session = FakeSessionClient()
        val coordinator = coordinator(true, human, bootstrap, session,
            provider = object : GoogleCredentialAcquirer {
                override suspend fun acquire(nonce: String): ProviderCredentialResult {
                    providerGate.pause()
                    return ProviderCredentialResult.Token("token-synthetic")
                }
            })
        val auth = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.continueWithGoogle() }
        providerGate.entered.await()
        coordinator.continueWithGoogle()
        coordinator.restartAuthentication()
        coordinator.retryDeviceBootstrap()
        coordinator.retryClientSession()
        assertEquals(1, human.challengeCalls)
        assertEquals(0, bootstrap.startCalls)
        assertEquals(0, session.startCalls)
        providerGate.release.complete(Unit)
        bootstrapGate.entered.await()
        repeat(3) {
            coordinator.retryDeviceBootstrap()
            coordinator.continueWithGoogle()
            coordinator.maintainClientSession()
        }
        assertEquals(1, human.challengeCalls)
        assertEquals(0, session.startCalls)
        bootstrapGate.release.complete(Unit)
        auth.join()
        assertEquals(1, bootstrap.startCalls)
        assertEquals(1, session.startCalls)
    }

    @Test
    fun expectedHttp200IsParsedAndImmediatelyContinuesToDeviceBootstrap() = runBlocking {
        val events = mutableListOf<String>()
        val transport = object : HumanAuthTransport {
            override suspend fun post(path: String, body: String): TransportResponse {
                return if (path.endsWith("verify-and-continue")) {
                    TransportResponse(200, """{"status":"HUMAN_IDENTITY_VALIDATED","human_identity_id":"$HUMAN_ID","continuation_grant":{"token":"$GRANT_TOKEN","purpose":"DEVICE_BOOTSTRAP","expires_at":"2030-01-01T00:05:00.842485Z"}}""")
                } else {
                    TransportResponse(201, """{"challenge_id":"synthetic-challenge","nonce":"synthetic-nonce","expires_at":"2030-01-01T00:00:00Z"}""")
                }
            }
        }
        val human = AttentionRouterHumanAuthClient("https://synthetic.invalid", transport,
            verifyTelemetry = { events += it.name })
        val bootstrap = FakeBootstrapClient()
        val coordinator = coordinator(true, human, bootstrap,
            sessionTelemetry = { event, _ -> events += event })
        coordinator.continueWithGoogle()
        assertEquals(1, bootstrap.startCalls)
        assertEquals(1, bootstrap.completeCalls)
        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
        assertNull(coordinator.takeContinuationGrant())
        val ordered = listOf("HUMAN_AUTH_VERIFY_HTTP_SUCCESS", "HUMAN_AUTH_VERIFY_PARSE_SUCCESS",
            "HUMAN_AUTH_BACKEND_VALIDATED", "DEVICE_BOOTSTRAP_START", "DEVICE_BOOTSTRAP_ACCEPTED", "CLIENT_SESSION_START")
        assertEquals(ordered, events.filter { it in ordered })
        assertFalse(events.contains("HUMAN_AUTH_VERIFY_PARSE_FAILURE"))
    }

    @Test
    fun malformedHttp200DoesNotBootstrapOrTriggerAutomaticSessionRetry() = runBlocking {
        val events = mutableListOf<String>()
        val rejected = mutableListOf<Map<String, String>>()
        val transport = object : HumanAuthTransport {
            override suspend fun post(path: String, body: String) = if (path.endsWith("verify-and-continue")) {
                TransportResponse(200, """{"status":"HUMAN_IDENTITY_VALIDATED","human_identity_id":"$HUMAN_ID","continuation_grant":null}""")
            } else {
                TransportResponse(201, """{"challenge_id":"synthetic-challenge","nonce":"synthetic-nonce","expires_at":"2030-01-01T00:00:00Z"}""")
            }
        }
        val human = AttentionRouterHumanAuthClient("https://synthetic.invalid", transport,
            verifyTelemetry = { events += it.name })
        val bootstrap = FakeBootstrapClient()
        val session = FakeSessionClient()
        val coordinator = coordinator(true, human, bootstrap, session,
            sessionTelemetry = { event, fields ->
                events += event
                if (event == "HUMAN_AUTH_REJECTED") rejected += fields
            })
        coordinator.continueWithGoogle()
        repeat(5) { coordinator.maintainClientSession(); coordinator.restoreClientSessionOnStartup() }
        assertEquals(0, bootstrap.startCalls)
        assertEquals(0, session.startCalls)
        assertTrue(coordinator.state.value is HumanIdentityState.Failure)
        assertTrue(events.contains("HUMAN_AUTH_VERIFY_HTTP_SUCCESS"))
        assertTrue(events.contains("HUMAN_AUTH_VERIFY_PARSE_FAILURE"))
        assertEquals(listOf(mapOf("category" to "UNEXPECTED_RESPONSE", "phase" to "VERIFY")), rejected)
        assertFalse(events.contains("HUMAN_AUTH_BACKEND_VALIDATED"))
        assertFalse(events.contains("DEVICE_BOOTSTRAP_START"))
        val rendered = events.toString()
        listOf(GRANT_TOKEN, HUMAN_ID, DEVICE_ID, SESSION_TOKEN).forEach { assertFalse(rendered.contains(it)) }
    }

    @Test
    fun pendingBootstrapFailureRequiresManualRetryAndNeverStartsAutomaticSession() = runBlocking {
        val bootstrap = SequencedBootstrapClient()
        val session = FakeSessionClient()
        val coordinator = coordinator(true, FakeHumanClient(), bootstrap, session)
        coordinator.continueWithGoogle()
        repeat(5) { coordinator.maintainClientSession(); coordinator.restoreClientSessionOnStartup(); coordinator.retryClientSession() }
        assertEquals(1, bootstrap.startCalls)
        assertEquals(0, session.startCalls)
        coordinator.retryDeviceBootstrap()
        assertEquals(2, bootstrap.startCalls)
        assertEquals(1, session.startCalls)
    }

    @Test
    fun cancellationDuringProviderLeavesManualRecoveryAndNoAutomaticSession() = runBlocking {
        val gate = SuspensionGate()
        val session = FakeSessionClient()
        val coordinator = coordinator(true, FakeHumanClient(), FakeBootstrapClient(), session,
            provider = object : GoogleCredentialAcquirer {
                override suspend fun acquire(nonce: String): ProviderCredentialResult {
                    gate.pause()
                    return ProviderCredentialResult.Token("token-synthetic")
                }
            })
        val auth = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.continueWithGoogle() }
        gate.entered.await()
        auth.cancelAndJoin()
        repeat(5) { coordinator.maintainClientSession() }
        assertTrue(coordinator.state.value is HumanIdentityState.Failure)
        assertEquals(0, session.startCalls)
        coordinator.restartAuthentication()
        coordinator.continueWithExistingDevice()
        assertEquals(1, session.startCalls)
    }

    @Test
    fun invalidPreviousTenantHintRequiresSelectionAfterGoogleBootstrap() = runBlocking {
        val store = FakeSessionStore(sessionCredential(tenantId = "tnt_previous_synthetic"))
        val memberships = dualTenantMemberships()
        val session = TenantSwitchSessionClient(memberships = memberships + ClientTenantMembership("ctm_previous", "tnt_previous_synthetic", ClientTenantRole.OWNER))
        val bootstrap = FakeBootstrapClient(complete = DeviceBootstrapCompleteResult.Success(established(memberships = memberships)))
        val coordinator = coordinator(true, FakeHumanClient(), bootstrap, session, store)
        coordinator.restoreClientSessionOnStartup()
        coordinator.continueWithGoogle()
        assertEquals(ClientSessionState.AwaitingTenantSelection(memberships), coordinator.sessionState.value)
        assertEquals(0, session.startCalls)
        assertTrue(coordinator.selectTenantForSession(TARGET_TENANT))
        assertEquals(listOf(TARGET_TENANT), session.requestedTenantIds)
    }

    @Test
    fun singleTenantAndValidPreviousHintRemainAutomaticAfterExclusiveBootstrap() = runBlocking {
        for (reauth in listOf(false, true)) {
            val memberships = if (reauth) dualTenantMemberships() else dualTenantMemberships().take(1)
            val hint = if (reauth) TARGET_TENANT else SOURCE_TENANT
            val gate = SuspensionGate()
            val bootstrap = object : FakeBootstrapClient(
                complete = DeviceBootstrapCompleteResult.Success(established(memberships = memberships)),
            ) {
                override suspend fun start(
                    grant: HumanAuthContinuationGrant,
                    publicKeySpkiB64Url: String,
                    canonicalDeviceName: String,
                    roles: Set<DeviceBootstrapRole>,
                ): DeviceBootstrapChallengeResult {
                    gate.pause()
                    return super.start(grant, publicKeySpkiB64Url, canonicalDeviceName, roles)
                }
            }
            val store = FakeSessionStore(if (reauth) sessionCredential(tenantId = hint) else null)
            val session = TenantSwitchSessionClient(memberships = memberships)
            val coordinator = coordinator(true, FakeHumanClient(), bootstrap, session, store)
            if (reauth) coordinator.restoreClientSessionOnStartup()
            val auth = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.continueWithGoogle() }
            gate.entered.await()
            repeat(3) { coordinator.maintainClientSession(); coordinator.restoreClientSessionOnStartup() }
            assertEquals(0, session.startCalls)
            gate.release.complete(Unit)
            auth.join()
            assertEquals(1, bootstrap.startCalls)
            assertEquals(listOf(hint), session.requestedTenantIds)
            assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
            assertNull(coordinator.takeContinuationGrant())
            repeat(3) { coordinator.maintainClientSession() }
            assertEquals(1, session.startCalls)
        }
    }

    @Test
    fun automaticSessionFailureDoesNotRepeatChallengeUntilManualRetry() = runBlocking {
        val session = FakeSessionClient(complete = ClientSessionCompleteResult.Failure(
            ClientSessionErrorCode.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED))
        val coordinator = coordinator(true, FakeHumanClient(), FakeBootstrapClient(), session)
        coordinator.restoreClientSessionOnStartup()
        assertEquals(1, session.startCalls)
        repeat(5) { coordinator.maintainClientSession(); coordinator.restoreClientSessionOnStartup() }
        assertEquals(1, session.startCalls)
        coordinator.retryClientSession()
        assertEquals(2, session.startCalls)
    }

    @Test
    fun googleAdmissionWaitsForExistingMaintenanceAndBlocksFurtherSessionAttempts() = runBlocking {
        val gate = SuspensionGate()
        val human = FakeHumanClient()
        val session = object : FakeSessionClient() {
            override suspend fun start(publicKeySpkiB64Url: String, requestedTenantId: String?): ClientSessionChallengeResult {
                val result = super.start(publicKeySpkiB64Url, requestedTenantId)
                gate.pause()
                return result
            }
        }
        val coordinator = coordinator(true, human, FakeBootstrapClient(), session)
        val maintenance = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.maintainClientSession() }
        gate.entered.await()
        val auth = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.continueWithGoogle() }
        assertTrue(auth.isActive)
        assertEquals(0, human.challengeCalls)
        repeat(3) { coordinator.maintainClientSession(); coordinator.retryClientSession(); coordinator.continueWithGoogle() }
        assertEquals(1, session.startCalls)
        gate.release.complete(Unit)
        maintenance.join()
        auth.join()
        assertEquals(1, human.challengeCalls)
        assertEquals(2, session.startCalls)
        assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
    }

    @Test
    fun multipleMembershipsRequireSelectionEvenWithUnexpectedInitialTenantHint() = runBlocking {
        val memberships = dualTenantMemberships()
        val bootstrap = FakeBootstrapClient(complete = DeviceBootstrapCompleteResult.Success(
            established(memberships = memberships, initialTenantId = SOURCE_TENANT)))
        val session = TenantSwitchSessionClient(memberships = memberships)
        val coordinator = coordinator(true, FakeHumanClient(), bootstrap, session)
        coordinator.continueWithGoogle()
        assertEquals(ClientSessionState.AwaitingTenantSelection(memberships), coordinator.sessionState.value)
        assertEquals(0, session.startCalls)
    }

    @Test
    fun cancellationAfterValidationPreservesGrantForExclusiveBootstrapRetry() = runBlocking {
        val gate = SuspensionGate()
        var attempts = 0
        val bootstrap = object : FakeBootstrapClient() {
            override suspend fun start(
                grant: HumanAuthContinuationGrant,
                publicKeySpkiB64Url: String,
                canonicalDeviceName: String,
                roles: Set<DeviceBootstrapRole>,
            ): DeviceBootstrapChallengeResult {
                attempts++
                if (attempts == 1) gate.pause()
                return super.start(grant, publicKeySpkiB64Url, canonicalDeviceName, roles)
            }
        }
        val session = FakeSessionClient()
        val coordinator = coordinator(true, FakeHumanClient(), bootstrap, session)
        val auth = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.continueWithGoogle() }
        gate.entered.await()
        auth.cancelAndJoin()
        assertTrue(coordinator.state.value is HumanIdentityState.Validated)
        assertTrue(coordinator.bootstrapState.value is DeviceBootstrapState.Failure)
        repeat(5) {
            coordinator.maintainClientSession()
            coordinator.restoreClientSessionOnStartup()
            coordinator.retryClientSession()
        }
        assertEquals(1, attempts)
        assertEquals(0, session.startCalls)
        coordinator.retryDeviceBootstrap()
        assertEquals(2, attempts)
        assertEquals(1, bootstrap.completeCalls)
        assertEquals(1, session.startCalls)
        assertNull(coordinator.takeContinuationGrant())
    }

    private class SuspensionGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun pause() { entered.complete(Unit); release.await() }
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
        sessionTelemetry: (String, Map<String, String>) -> Unit = { _, _ -> },
        provider: GoogleCredentialAcquirer = object : GoogleCredentialAcquirer {
            override suspend fun acquire(nonce: String) = ProviderCredentialResult.Token("token-synthetic")
        },
    ) = OnboardingCoordinator(
        ready = ready,
        config = OnboardingConfiguration(
            "https://synthetic.invalid",
            "client-id-synthetic",
            "Synthetic Android",
        ),
        client = client,
        provider = provider,
        bootstrapClient = bootstrapClient,
        sessionClient = sessionClient,
        gmailClient = gmailClient,
        gmailAuthorization = gmailAuthorization,
        devicePublicKeySpki = { "A".repeat(120) },
        signDeviceChallenge = { "c".repeat(96) },
        sessionStore = store,
        now = now,
        sessionTelemetry = sessionTelemetry,
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

    private open class FakeHumanClient(
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
        private val directory: AuthenticatedTenantDirectoryResult =
            AuthenticatedTenantDirectoryResult.Failure(
                ClientSessionErrorCode.CLIENT_SESSION_UNAVAILABLE,
            ),
    ) : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0
        var directoryCalls = 0
        val requestedTenantIds = mutableListOf<String?>()

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            requestedTenantIds += requestedTenantId
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

        override suspend fun tenantDirectory(
            session: ClientSessionCredential,
        ): AuthenticatedTenantDirectoryResult {
            directoryCalls++
            return directory
        }
    }

    private class MaintenanceSessionClient : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0
        val requestedTenantIds = mutableListOf<String?>()

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            requestedTenantIds += requestedTenantId
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
            return AuthenticatedBootstrapResult.Success(
                authenticatedBootstrap(session.expiresAt),
            )
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

    private class TransientStartThenSuccessSessionClient : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0
        val requestedTenantIds = mutableListOf<String?>()

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            requestedTenantIds += requestedTenantId
            return if (startCalls == 1) {
                ClientSessionChallengeResult.Failure(ClientSessionErrorCode.NETWORK_FAILURE)
            } else {
                ClientSessionChallengeResult.Success(sessionChallenge())
            }
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

    private class RestoreRejectedThenFreshSessionClient : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0
        val requestedTenantIds = mutableListOf<String?>()

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            requestedTenantIds += requestedTenantId
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

    private class TenantSwitchSessionClient(
        private val memberships: List<ClientTenantMembership>,
        private val startFailure: ClientSessionErrorCode? = null,
        private val issuedTenantOverride: String? = null,
        private val issuedHumanIdentityOverride: String? = null,
        private val issuedDeviceIdOverride: String? = null,
    ) : ClientSessionClient {
        var startCalls = 0
        var completeCalls = 0
        var bootstrapCalls = 0
        val requestedTenantIds = mutableListOf<String?>()
        private var requestedTenantId: String? = null

        override suspend fun start(
            publicKeySpkiB64Url: String,
            requestedTenantId: String?,
        ): ClientSessionChallengeResult {
            startCalls++
            requestedTenantIds += requestedTenantId
            this.requestedTenantId = requestedTenantId
            if (startFailure != null) {
                return ClientSessionChallengeResult.Failure(startFailure)
            }
            return ClientSessionChallengeResult.Success(sessionChallenge())
        }

        override suspend fun complete(
            challengeId: String,
            signatureB64Url: String,
        ): ClientSessionCompleteResult {
            completeCalls++
            val tenantId =
                issuedTenantOverride
                    ?: requestedTenantId
                    ?: SOURCE_TENANT
            return ClientSessionCompleteResult.Success(
                sessionCredential(
                    tenantId = tenantId,
                    humanIdentityId = issuedHumanIdentityOverride ?: HUMAN_ID,
                    deviceId = issuedDeviceIdOverride ?: DEVICE_ID,
                ),
            )
        }

        override suspend fun bootstrap(
            session: ClientSessionCredential,
        ): AuthenticatedBootstrapResult {
            bootstrapCalls++
            return AuthenticatedBootstrapResult.Success(
                authenticatedBootstrap(
                    expiresAt = session.expiresAt,
                    activeTenantId = session.tenantId,
                    memberships = memberships,
                ),
            )
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
        const val OTHER_HUMAN_ID = "hid_otheropaqueidentity123"
        const val OTHER_DEVICE_ID = "cdev_otherdevice12345678901"
        const val SOURCE_TENANT = "tnt_synthetic"
        const val TARGET_TENANT = "tnt_target"

        fun dualTenantMemberships() = listOf(
            ClientTenantMembership(
                "ctm_source",
                SOURCE_TENANT,
                ClientTenantRole.OWNER,
            ),
            ClientTenantMembership(
                "ctm_target",
                TARGET_TENANT,
                ClientTenantRole.OWNER,
            ),
        )

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
            tenantId: String = SOURCE_TENANT,
            humanIdentityId: String = HUMAN_ID,
            deviceId: String = DEVICE_ID,
        ) = ClientSessionCredential(
            token = SESSION_TOKEN,
            sessionId = "csn_examplesession12345678901",
            expiresAt = expiresAt,
            humanIdentityId = humanIdentityId,
            deviceId = deviceId,
            tenantId = tenantId,
        )

        fun authenticatedBootstrap(
            expiresAt: Instant = Instant.parse("2030-01-01T00:15:00Z"),
            activeTenantId: String = SOURCE_TENANT,
            memberships: List<ClientTenantMembership> = listOf(
                ClientTenantMembership(
                    "ctm_source",
                    SOURCE_TENANT,
                    ClientTenantRole.OWNER,
                ),
            ),
        ) = AuthenticatedClientBootstrap(
            humanIdentityId = HUMAN_ID,
            activeTenantId = activeTenantId,
            memberships = memberships,
            device = ClientDevice(
                DEVICE_ID,
                "sha256:" + "f".repeat(64),
                "Synthetic Android",
                setOf(DeviceBootstrapRole.CLIENT, DeviceBootstrapRole.CAPABILITY_NODE),
            ),
            sessionExpiresAt = expiresAt,
            serverTime = Instant.parse("2030-01-01T00:08:00Z"),
        )

        fun established(
            humanId: String = HUMAN_ID,
            memberships: List<ClientTenantMembership> = listOf(
                ClientTenantMembership(
                    "ctm_synthetic",
                    "tnt_synthetic",
                    ClientTenantRole.OWNER,
                ),
            ),
            initialTenantId: String? = memberships.singleOrNull()?.tenantId,
        ) = DeviceBootstrapEstablished(
            humanIdentityId = humanId,
            memberships = memberships,
            initialTenantId = initialTenantId,
            device = ClientDevice(
                DEVICE_ID,
                "sha256:" + "f".repeat(64),
                "Synthetic Android",
                setOf(DeviceBootstrapRole.CLIENT, DeviceBootstrapRole.CAPABILITY_NODE),
            ),
        )
    }
}
