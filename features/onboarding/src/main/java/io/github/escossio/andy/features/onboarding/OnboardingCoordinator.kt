package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.core.humanidentity.*
import io.github.escossio.andy.integrations.googleidentity.*
import io.github.escossio.andy.sdk.clientapi.*
import kotlinx.coroutines.flow.*

data class OnboardingConfiguration(val clientApiBaseUrl:String,val googleWebClientId:String)
class OnboardingCoordinator(ready:Boolean,private val config:OnboardingConfiguration,private val client:HumanAuthClient,private val provider:GoogleCredentialAcquirer,private val engine:HumanIdentityEngine=HumanIdentityEngine(ready)) {
 private val mutable=MutableStateFlow(engine.state);val state:StateFlow<HumanIdentityState>=mutable.asStateFlow()
 suspend fun continueWithGoogle() {
  if(engine.state==HumanIdentityState.DeviceIdentityUnavailable){mutable.value=engine.begin();return}
  if(config.clientApiBaseUrl.isBlank()||config.googleWebClientId.isBlank()){mutable.value=engine.fail(HumanIdentityFailure.CONFIGURATION_MISSING);return}
  mutable.value=engine.begin()
  val challenge=when(val result=client.requestChallenge()){is ChallengeResult.Success->result.challenge;is ChallengeResult.Failure->{mutable.value=engine.fail(result.error.failure());return}}
  mutable.value=engine.challengeReceived()
  when(val credential=provider.acquire(challenge.nonce)) {
   ProviderCredentialResult.Cancelled->mutable.value=engine.fail(HumanIdentityFailure.PROVIDER_CANCELLED)
   ProviderCredentialResult.Unavailable->mutable.value=engine.fail(HumanIdentityFailure.PROVIDER_UNAVAILABLE)
   is ProviderCredentialResult.Token->{mutable.value=engine.providerCredentialReceived();when(val verified=client.verify(challenge.challengeId,credential.idToken)){is VerifyResult.Success->mutable.value=engine.backendValidated(verified.validated.humanIdentityId);is VerifyResult.Failure->mutable.value=engine.fail(verified.error.failure())}}
  }
 }
 fun retry(){mutable.value=engine.retry()}
 private fun HumanAuthErrorCode.failure()=when(this){HumanAuthErrorCode.NETWORK_FAILURE->HumanIdentityFailure.NETWORK_FAILURE;HumanAuthErrorCode.CHALLENGE_EXPIRED->HumanIdentityFailure.CHALLENGE_EXPIRED;HumanAuthErrorCode.CHALLENGE_CONSUMED->HumanIdentityFailure.CHALLENGE_CONSUMED;HumanAuthErrorCode.CREDENTIAL_REJECTED->HumanIdentityFailure.CREDENTIAL_REJECTED;HumanAuthErrorCode.NONCE_MISMATCH->HumanIdentityFailure.NONCE_MISMATCH;HumanAuthErrorCode.PROVIDER_UNAVAILABLE->HumanIdentityFailure.PROVIDER_UNAVAILABLE;HumanAuthErrorCode.UNEXPECTED_RESPONSE->HumanIdentityFailure.UNEXPECTED_RESPONSE}
}
