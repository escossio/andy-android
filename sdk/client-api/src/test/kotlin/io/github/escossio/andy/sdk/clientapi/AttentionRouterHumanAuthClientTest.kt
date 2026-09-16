package io.github.escossio.andy.sdk.clientapi
import org.junit.Assert.*
import org.junit.Test
class AttentionRouterHumanAuthClientTest {
 private fun c(r:TransportResponse)=AttentionRouterHumanAuthClient("https://synthetic.invalid",object:HumanAuthTransport{override fun post(path:String,body:String)=r})
 @Test fun challenge201() { assertEquals(ChallengeResult.Success(GoogleChallenge("c","n","2030")),c(TransportResponse(201,"{\"challenge_id\":\"c\",\"nonce\":\"n\",\"expires_at\":\"2030\"}")).requestChallenge()) }
 @Test fun verify200() { assertEquals(VerifyResult.Success(HumanIdentityValidated("h")),c(TransportResponse(200,"{\"status\":\"HUMAN_IDENTITY_VALIDATED\",\"human_identity_id\":\"h\"}")).verify("c","token")) }
 @Test fun allContractErrorsAndMalformedFailClosed(){ for(code in listOf("CHALLENGE_EXPIRED","CHALLENGE_CONSUMED","CREDENTIAL_REJECTED","NONCE_MISMATCH","PROVIDER_UNAVAILABLE","NETWORK_FAILURE"))assertEquals(ChallengeResult.Failure(HumanAuthErrorCode.valueOf(code)),c(TransportResponse(400,"{\"code\":\"$code\"}")).requestChallenge());assertEquals(ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE),c(TransportResponse(201,"bad")).requestChallenge()) }
 @Test fun unexpectedVerifyNeverAuthorizes(){assertFalse(c(TransportResponse(200,"{\"status\":\"LOCAL_OK\"}")).verify("c","token") is VerifyResult.Success)}
}
