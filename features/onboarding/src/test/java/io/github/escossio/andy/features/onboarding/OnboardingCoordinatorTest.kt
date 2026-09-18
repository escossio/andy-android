package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.core.humanidentity.HumanIdentityReference
import io.github.escossio.andy.core.humanidentity.HumanIdentityState
import io.github.escossio.andy.integrations.googleidentity.GoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.ProviderCredentialResult
import io.github.escossio.andy.sdk.clientapi.ChallengeResult
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
import io.github.escossio.andy.sdk.clientapi.VerifyResult
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
        assertEquals("cdev_synthetic", established.device.deviceId)
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
    fun restartAuthenticationDiscardsUnconsumedGrantAndBootstrapState() = runBlocking {
        val bootstrap = FakeBootstrapClient(
            start = DeviceBootstrapChallengeResult.Failure(
                DeviceBootstrapErrorCode.NETWORK_FAILURE,
            ),
        )
        val coordinator = coordinator(true, FakeHumanClient(), bootstrap)
        coordinator.continueWithGoogle()

        coordinator.restartAuthentication()

        assertNull(coordinator.takeContinuationGrant())
        assertEquals(HumanIdentityState.Unauthenticated, coordinator.state.value)
        assertSame(DeviceBootstrapState.Idle, coordinator.bootstrapState.value)
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

    private fun coordinator(
        ready: Boolean,
        client: HumanAuthClient,
        bootstrapClient: DeviceBootstrapClient,
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
        devicePublicKeySpki = { "A".repeat(120) },
        signDeviceChallenge = { "c".repeat(96) },
    )

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

    private companion object {
        const val HUMAN_ID = "hid_exampleopaqueidentity123"
        const val GRANT_TOKEN = "hcg_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

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
                "cdev_synthetic",
                "sha256:" + "f".repeat(64),
                "Synthetic Android",
                setOf(DeviceBootstrapRole.CLIENT, DeviceBootstrapRole.CAPABILITY_NODE),
            ),
        )
    }
}
