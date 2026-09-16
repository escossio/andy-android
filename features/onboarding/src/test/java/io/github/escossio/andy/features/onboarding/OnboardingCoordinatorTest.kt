package io.github.escossio.andy.features.onboarding
import io.github.escossio.andy.core.humanidentity.HumanIdentityState
import io.github.escossio.andy.integrations.googleidentity.*
import io.github.escossio.andy.sdk.clientapi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class OnboardingCoordinatorTest {
 @Test fun notReadyDoesNotStart()=runBlocking{var calls=0;val c=coordinator(false,object:HumanAuthClient{override suspend fun requestChallenge():ChallengeResult{calls++;return ChallengeResult.Success(challenge())};override suspend fun verify(challengeId:String,idToken:String)=VerifyResult.Success(HumanIdentityValidated("h"))});c.continueWithGoogle();assertEquals(0,calls);assertEquals(HumanIdentityState.DeviceIdentityUnavailable,c.state.value)}
 @Test fun backendSuccessShowsValidated()=runBlocking{val c=coordinator(true,ok());c.continueWithGoogle();assertEquals(HumanIdentityState.Validated("h"),c.state.value)}
 @Test fun failureCanRetry()=runBlocking{val c=coordinator(true,object:HumanAuthClient{override suspend fun requestChallenge()=ChallengeResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE);override suspend fun verify(challengeId:String,idToken:String)=VerifyResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE)});c.continueWithGoogle();assertFalse(c.state.value is HumanIdentityState.Validated);c.retry();assertEquals(HumanIdentityState.Unauthenticated,c.state.value)}
 private fun coordinator(ready:Boolean,client:HumanAuthClient)=OnboardingCoordinator(ready,OnboardingConfiguration("https://synthetic.invalid","client-id-synthetic"),client,object:GoogleCredentialAcquirer{override suspend fun acquire(nonce:String)=ProviderCredentialResult.Token("token-synthetic")})
 private fun challenge()=GoogleChallenge("c","n","2030")
 private fun ok()=object:HumanAuthClient{override suspend fun requestChallenge()=ChallengeResult.Success(challenge());override suspend fun verify(challengeId:String,idToken:String)=VerifyResult.Success(HumanIdentityValidated("h"))}
}
