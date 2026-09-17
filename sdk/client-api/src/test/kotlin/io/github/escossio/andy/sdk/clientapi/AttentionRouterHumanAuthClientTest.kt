package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class AttentionRouterHumanAuthClientTest {
    @Test fun challenge201WithExactContractBodySucceeds() = runBlocking {
        assertEquals(ChallengeResult.Success(challenge()), client(TransportResponse(201, challengeJson())).requestChallenge())
    }

    @Test fun verify200WithValidatedStatusSucceeds() = runBlocking {
        assertEquals(VerifyResult.Success(HumanIdentityValidated(HUMAN_ID)), client(TransportResponse(200, "{\"status\":\"HUMAN_IDENTITY_VALIDATED\",\"human_identity_id\":\"$HUMAN_ID\"}")).verify(CHALLENGE_ID, TOKEN))
    }

    @Test fun everyOfficialHumanAuthErrorIsMappedLiterally() = runBlocking {
        for ((wire, local) in mapOf(
            "HUMAN_AUTH_DISABLED" to HumanAuthErrorCode.HUMAN_AUTH_DISABLED,
            "HUMAN_AUTH_CHALLENGE_NOT_FOUND" to HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_NOT_FOUND,
            "HUMAN_AUTH_CHALLENGE_EXPIRED" to HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_EXPIRED,
            "HUMAN_AUTH_CHALLENGE_CONSUMED" to HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_CONSUMED,
            "HUMAN_AUTH_CREDENTIAL_REJECTED" to HumanAuthErrorCode.HUMAN_AUTH_CREDENTIAL_REJECTED,
            "HUMAN_AUTH_NONCE_MISMATCH" to HumanAuthErrorCode.HUMAN_AUTH_NONCE_MISMATCH,
            "HUMAN_AUTH_PROVIDER_UNAVAILABLE" to HumanAuthErrorCode.HUMAN_AUTH_PROVIDER_UNAVAILABLE,
        )) assertEquals(ChallengeResult.Failure(local), client(TransportResponse(400, "{\"code\":\"$wire\"}")).requestChallenge())
    }

    @Test fun missingEmptyAndMalformedSuccessBodiesFailClosed() = runBlocking {
        for (body in listOf<String?>(null, "", "not-json")) {
            assertEquals(ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE), client(TransportResponse(201, body)).requestChallenge())
            assertEquals(VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE), client(TransportResponse(200, body)).verify(CHALLENGE_ID, TOKEN))
        }
    }

    @Test fun incompatibleStatusAndUnexpectedResponseNeverAuthorize() = runBlocking {
        val result = client(TransportResponse(200, "{\"status\":\"LOCAL_GOOGLE_OK\",\"human_identity_id\":\"$HUMAN_ID\"}")).verify(CHALLENGE_ID, TOKEN)
        assertEquals(VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE), result)
        assertFalse(result is VerifyResult.Success)
    }

    @Test fun networkFailureComesOnlyFromTransportException() = runBlocking {
        val failing = AttentionRouterHumanAuthClient("https://synthetic.invalid", object : HumanAuthTransport {
            override suspend fun post(path: String, body: String): TransportResponse = throw IllegalStateException("synthetic")
        })
        assertEquals(ChallengeResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE), failing.requestChallenge())
        assertEquals(ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE), client(TransportResponse(400, "{\"code\":\"NETWORK_FAILURE\"}")).requestChallenge())
    }

    @Test fun httpBaseUrlIsRejectedBeforeAnyConnection() = runBlocking {
        try {
            OkHttpHumanAuthTransport("http://synthetic.invalid").post("/synthetic", "{}")
            fail("HTTP base URL must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun client(response: TransportResponse) = AttentionRouterHumanAuthClient("https://synthetic.invalid", object : HumanAuthTransport { override suspend fun post(path: String, body: String) = response })
    private fun challenge() = GoogleChallenge(CHALLENGE_ID, NONCE, "2030-01-01T00:00:00Z")
    private fun challengeJson() = "{\"challenge_id\":\"$CHALLENGE_ID\",\"nonce\":\"$NONCE\",\"expires_at\":\"2030-01-01T00:00:00Z\"}"
    private companion object { const val CHALLENGE_ID="hac_examplechallenge123456789"; const val NONCE="nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn"; const val HUMAN_ID="hid_exampleopaqueidentity123"; const val TOKEN="synthetic-token-value-that-is-at-least-32-characters" }
}
