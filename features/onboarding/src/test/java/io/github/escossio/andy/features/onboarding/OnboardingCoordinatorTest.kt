package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.core.humanidentity.HumanIdentityReference
import io.github.escossio.andy.core.humanidentity.HumanIdentityState
import io.github.escossio.andy.integrations.googleidentity.GoogleCredentialAcquirer
import io.github.escossio.andy.integrations.googleidentity.ProviderCredentialResult
import io.github.escossio.andy.sdk.clientapi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class OnboardingCoordinatorTest {
    @Test fun notReadyDoesNotStart() = runBlocking {
        val client = FakeClient()
        val coordinator = coordinator(false, client)
        coordinator.continueWithGoogle()
        assertEquals(0, client.challengeCalls)
        assertEquals(HumanIdentityState.DeviceIdentityUnavailable, coordinator.state.value)
    }

    @Test fun backendSuccessKeepsGrantOutOfPublicUiStateAndOnlyInMemory() = runBlocking {
        val grant = grant()
        val client = FakeClient(continuation = ContinuationResult.Success(
            HumanIdentityContinuation(HumanIdentityReference("hid_exampleopaqueidentity123"), grant),
        ))
        val coordinator = coordinator(true, client)
        coordinator.continueWithGoogle()

        assertEquals(
            HumanIdentityState.Validated(HumanIdentityReference("hid_exampleopaqueidentity123")),
            coordinator.state.value,
        )
        assertFalse(coordinator.state.value.toString().contains(GRANT_TOKEN))
        assertSame(grant, coordinator.takeContinuationGrant())
        assertNull(coordinator.takeContinuationGrant())
    }

    @Test fun retryDiscardsUnconsumedInMemoryGrant() = runBlocking {
        val coordinator = coordinator(true, FakeClient(continuation = ContinuationResult.Success(
            HumanIdentityContinuation(HumanIdentityReference("hid_exampleopaqueidentity123"), grant()),
        )))
        coordinator.continueWithGoogle()
        coordinator.retry()
        assertNull(coordinator.takeContinuationGrant())
        assertEquals(HumanIdentityState.Unauthenticated, coordinator.state.value)
    }

    @Test fun failureCanRetryWithoutGrant() = runBlocking {
        val coordinator = coordinator(true, FakeClient(challenge = ChallengeResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE)))
        coordinator.continueWithGoogle()
        assertFalse(coordinator.state.value is HumanIdentityState.Validated)
        assertNull(coordinator.takeContinuationGrant())
        coordinator.retry()
        assertEquals(HumanIdentityState.Unauthenticated, coordinator.state.value)
    }

    private fun coordinator(ready: Boolean, client: HumanAuthClient) = OnboardingCoordinator(
        ready,
        OnboardingConfiguration("https://synthetic.invalid", "client-id-synthetic"),
        client,
        object : GoogleCredentialAcquirer {
            override suspend fun acquire(nonce: String) = ProviderCredentialResult.Token("token-synthetic")
        },
    )

    private class FakeClient(
        private val challenge: ChallengeResult = ChallengeResult.Success(
            GoogleChallenge("hac_examplechallenge123456789", "n".repeat(32), "2030-01-01T00:00:00Z"),
        ),
        private val continuation: ContinuationResult = ContinuationResult.Success(
            HumanIdentityContinuation(HumanIdentityReference("hid_exampleopaqueidentity123"), grant()),
        ),
    ) : HumanAuthClient {
        var challengeCalls = 0
        override suspend fun requestChallenge(): ChallengeResult {
            challengeCalls++
            return challenge
        }
        override suspend fun verify(challengeId: String, idToken: String) =
            VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE)
        override suspend fun verifyAndContinue(challengeId: String, idToken: String) = continuation
    }

    private companion object {
        const val GRANT_TOKEN = "hcg_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        fun grant() = HumanAuthContinuationGrant(
            GRANT_TOKEN, HumanAuthContinuationPurpose.DEVICE_BOOTSTRAP, Instant.parse("2030-01-01T00:05:00Z"),
        )
    }
}
