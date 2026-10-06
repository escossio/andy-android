package io.github.escossio.andy

import io.github.escossio.andy.core.humanidentity.HumanIdentityReference
import io.github.escossio.andy.features.onboarding.ClientSessionState
import io.github.escossio.andy.features.onboarding.DeviceBootstrapState
import io.github.escossio.andy.features.onboarding.OnboardingConfiguration
import io.github.escossio.andy.features.onboarding.OnboardingCoordinator
import io.github.escossio.andy.integrations.googleidentity.GoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.ProviderCredentialResult
import io.github.escossio.andy.sdk.clientapi.AuthenticatedBootstrapResult
import io.github.escossio.andy.sdk.clientapi.AuthenticatedClientBootstrap
import io.github.escossio.andy.sdk.clientapi.ChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientDevice
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallenge
import io.github.escossio.andy.sdk.clientapi.ClientSessionChallengeResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionClient
import io.github.escossio.andy.sdk.clientapi.ClientSessionCompleteResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import io.github.escossio.andy.sdk.clientapi.ClientTenantMembership
import io.github.escossio.andy.sdk.clientapi.ClientTenantRole
import io.github.escossio.andy.sdk.clientapi.ContinuationResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapChallenge
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapChallengeResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapClient
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapCompleteResult
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapEstablished
import io.github.escossio.andy.sdk.clientapi.DeviceBootstrapRole
import io.github.escossio.andy.sdk.clientapi.GoogleChallenge
import io.github.escossio.andy.sdk.clientapi.HumanAuthClient
import io.github.escossio.andy.sdk.clientapi.HumanAuthContinuationGrant
import io.github.escossio.andy.sdk.clientapi.HumanAuthContinuationPurpose
import io.github.escossio.andy.sdk.clientapi.HumanIdentityContinuation
import io.github.escossio.andy.sdk.clientapi.VerifyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AndySessionViewModelTest {
    @Test
    fun foregroundReturnDuringProviderPreservesAuthAndSkipsSession() = runBlocking {
        exerciseCredentialManagerReturn(blockProvider = true)
    }

    @Test
    fun foregroundReturnDuringVerifyPreservesAuthAndSkipsSession() = runBlocking {
        exerciseCredentialManagerReturn(blockProvider = false)
    }

    private suspend fun CoroutineScope.exerciseCredentialManagerReturn(blockProvider: Boolean) {
        val fixture = AuthFixture()
        if (!blockProvider) fixture.providerRelease.complete(Unit)
        val retainedScope = CoroutineScope(coroutineContext + SupervisorJob())
        val screenScope = CoroutineScope(coroutineContext + SupervisorJob())
        val maintenancePasses = Channel<Unit>(Channel.UNLIMITED)
        val coordinator = fixture.coordinator()
        val runner = SessionLifecycleRunner(
            scope = retainedScope,
            maintainSession = {
                coordinator.maintainClientSession()
                maintenancePasses.send(Unit)
            },
            authoritativeDispatcher = Dispatchers.Unconfined,
        )

        try {
            withTimeout(5_000L) {
                val authStarted = CompletableDeferred<Job>()
                screenScope.launch {
                    authStarted.complete(runner.launchAuthoritative { coordinator.continueWithGoogle() }!!)
                }
                val auth = authStarted.await()
                if (blockProvider) fixture.providerStarted.await() else fixture.verifyStarted.await()

                runner.enterForeground()
                maintenancePasses.receive()
                assertEquals(0, fixture.sessionStarts)
                assertTrue(auth.isActive)
                assertNull(runner.launchAuthoritative { coordinator.retryClientSession() })

                runner.leaveForeground()
                screenScope.cancel() // Composition disposal during Activity recreation.
                assertFalse(auth.isCancelled)
                runner.enterForeground()
                maintenancePasses.receive()
                assertEquals(0, fixture.sessionStarts)
                assertTrue(auth.isActive)

                fixture.providerRelease.complete(Unit)
                fixture.verifyStarted.await()
                fixture.verifyRelease.complete(Unit)
                fixture.bootstrapStarted.await()
                assertEquals(1, fixture.bootstrapStarts)
                assertEquals(0, fixture.sessionStarts)

                runner.leaveForeground()
                runner.enterForeground()
                maintenancePasses.receive()
                assertEquals(0, fixture.sessionStarts)
                assertFalse(auth.isCancelled)

                fixture.bootstrapRelease.complete(Unit)
                auth.join()
                assertTrue(coordinator.bootstrapState.value is DeviceBootstrapState.Established)
                assertTrue(coordinator.sessionState.value is ClientSessionState.AwaitingTenantSelection)
                assertNull(coordinator.takeContinuationGrant())
                assertEquals(0, fixture.sessionStarts)

                repeat(3) {
                    runner.leaveForeground()
                    runner.enterForeground()
                    maintenancePasses.receive()
                    assertEquals(0, fixture.sessionStarts)
                }

                val selection = runner.launchAuthoritative {
                    assertTrue(coordinator.selectTenantForSession(TARGET_TENANT))
                }
                assertNotNull(selection)
                selection!!.join()
                assertEquals(1, fixture.sessionStarts)
                assertEquals(TARGET_TENANT, fixture.requestedTenant)
                assertTrue(coordinator.sessionState.value is ClientSessionState.Connected)
                coordinator.maintainClientSession()
                assertEquals(1, fixture.sessionStarts)
            }
        } finally {
            runner.leaveForeground()
            screenScope.cancel()
            retainedScope.cancel()
            maintenancePasses.cancel()
        }
    }

    private class AuthFixture : HumanAuthClient, DeviceBootstrapClient {
        val providerStarted = CompletableDeferred<Unit>()
        val providerRelease = CompletableDeferred<Unit>()
        val verifyStarted = CompletableDeferred<Unit>()
        val verifyRelease = CompletableDeferred<Unit>()
        val bootstrapStarted = CompletableDeferred<Unit>()
        val bootstrapRelease = CompletableDeferred<Unit>()
        var bootstrapStarts = 0
        var sessionStarts = 0
        var requestedTenant: String? = null

        fun coordinator() = OnboardingCoordinator(
            ready = true,
            config = OnboardingConfiguration("https://example.test", "synthetic-client", "Synthetic Android"),
            client = this,
            provider = object : GoogleCredentialAcquirer {
                override suspend fun acquire(nonce: String): ProviderCredentialResult {
                    providerStarted.complete(Unit)
                    providerRelease.await()
                    return ProviderCredentialResult.Token("synthetic-provider-token")
                }
            },
            bootstrapClient = this,
            sessionClient = sessionClient,
            devicePublicKeySpki = { "k".repeat(120) },
            signDeviceChallenge = { "s".repeat(86) },
            now = { NOW },
            sessionTelemetry = { _, _ -> },
        )

        override suspend fun requestChallenge() = ChallengeResult.Success(
            GoogleChallenge("challenge_synthetic", "n".repeat(43), EXPIRES.toString()),
        )

        override suspend fun verify(challengeId: String, idToken: String): VerifyResult =
            error("This flow requires verifyAndContinue")

        override suspend fun verifyAndContinue(challengeId: String, idToken: String): ContinuationResult {
            verifyStarted.complete(Unit)
            verifyRelease.await()
            return ContinuationResult.Success(
                HumanIdentityContinuation(
                    HumanIdentityReference(HUMAN),
                    HumanAuthContinuationGrant("hcg_" + "g".repeat(43), HumanAuthContinuationPurpose.DEVICE_BOOTSTRAP, EXPIRES),
                ),
            )
        }

        override suspend fun start(
            grant: HumanAuthContinuationGrant,
            publicKeySpkiB64Url: String,
            canonicalDeviceName: String,
            roles: Set<DeviceBootstrapRole>,
        ): DeviceBootstrapChallengeResult {
            bootstrapStarts++
            bootstrapStarted.complete(Unit)
            bootstrapRelease.await()
            return DeviceBootstrapChallengeResult.Success(
                DeviceBootstrapChallenge("dbc_synthetic", "b".repeat(43), EXPIRES),
            )
        }

        override suspend fun complete(challengeId: String, signatureB64Url: String) =
            DeviceBootstrapCompleteResult.Success(
                DeviceBootstrapEstablished(HUMAN, MEMBERSHIPS, null, DEVICE),
            )

        private val sessionClient = object : ClientSessionClient {
            override suspend fun start(publicKeySpkiB64Url: String, requestedTenantId: String?): ClientSessionChallengeResult {
                sessionStarts++
                requestedTenant = requestedTenantId
                return ClientSessionChallengeResult.Success(
                    ClientSessionChallenge("csc_synthetic", "s".repeat(43), EXPIRES),
                )
            }

            override suspend fun complete(challengeId: String, signatureB64Url: String) =
                ClientSessionCompleteResult.Success(
                    ClientSessionCredential(
                        "cst_" + "s".repeat(43), "csn_syntheticsession123456789", EXPIRES,
                        HUMAN, DEVICE.deviceId, TARGET_TENANT,
                    ),
                )

            override suspend fun bootstrap(session: ClientSessionCredential) = AuthenticatedBootstrapResult.Success(
                AuthenticatedClientBootstrap(HUMAN, TARGET_TENANT, MEMBERSHIPS, DEVICE, EXPIRES, NOW),
            )
        }
    }

    companion object {
        private val NOW = Instant.parse("2030-01-01T00:00:00Z")
        private val EXPIRES = NOW.plusSeconds(900)
        private const val HUMAN = "hid_syntheticidentity123456789"
        private const val TARGET_TENANT = "tnt_synthetic_target"
        private val MEMBERSHIPS = listOf(
            ClientTenantMembership("ctm_synthetic_source", "tnt_synthetic_source", ClientTenantRole.OWNER),
            ClientTenantMembership("ctm_synthetic_target", TARGET_TENANT, ClientTenantRole.OWNER),
        )
        private val DEVICE = ClientDevice(
            "cdev_syntheticdevice123456789", "sha256:" + "f".repeat(64), "Synthetic Android", setOf(DeviceBootstrapRole.CLIENT),
        )
    }
}
