package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.core.humanidentity.*
import io.github.escossio.andy.integrations.googleidentity.*
import io.github.escossio.andy.sdk.clientapi.*
import kotlinx.coroutines.flow.*

data class OnboardingConfiguration(val clientApiBaseUrl:String,val googleWebClientId:String)
class OnboardingCoordinator(ready:Boolean,private val config:OnboardingConfiguration,private val client:HumanAuthClient,private val provider:GoogleCredentialAcquirer,private val engine:HumanIdentityEngine=HumanIdentityEngine(ready)) {
 private val mutable=MutableStateFlow(engine.state);val state: StateFlow<HumanIdentityState> = mutable.asStateFlow()
 private var continuationGrant:HumanAuthContinuationGrant?=null
 fun takeContinuationGrant():HumanAuthContinuationGrant? { val grant=continuationGrant;continuationGrant=null;return grant }
 suspend fun continueWithGoogle() {
  continuationGrant=null
  if(engine.state==HumanIdentityState.DeviceIdentityUnavailable){mutable.value=engine.begin();return}
  if(config.clientApiBaseUrl.isBlank()||config.googleWebClientId.isBlank()){mutable.value=engine.fail(HumanIdentityFailure.CONFIGURATION_MISSING);return}
  mutable.value=engine.begin()
  val challenge=when(val result=client.requestChallenge()){is ChallengeResult.Success->result.challenge;is ChallengeResult.Failure->{mutable.value=engine.fail(result.error.failure());return}}
  mutable.value=engine.challengeReceived()
  when(val credential=provider.acquire(challenge.nonce)) {
   ProviderCredentialResult.Cancelled->mutable.value=engine.fail(HumanIdentityFailure.PROVIDER_CANCELLED)
   ProviderCredentialResult.Unavailable->mutable.value=engine.fail(HumanIdentityFailure.PROVIDER_UNAVAILABLE)
   is ProviderCredentialResult.Token->{mutable.value=engine.providerCredentialReceived();when(val continued=client.verifyAndContinue(challenge.challengeId,credential.idToken)){is ContinuationResult.Success->{continuationGrant=continued.continuation.grant;mutable.value=engine.backendValidated(continued.continuation.identityReference)};is ContinuationResult.Failure->mutable.value=engine.fail(continued.error.failure())}}
  }
 }
 fun retry(){continuationGrant=null;mutable.value=engine.retry()}
 private fun HumanAuthErrorCode.failure()=when(this){HumanAuthErrorCode.NETWORK_FAILURE->HumanIdentityFailure.NETWORK_FAILURE;HumanAuthErrorCode.HUMAN_AUTH_DISABLED->HumanIdentityFailure.HUMAN_AUTH_DISABLED;HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_NOT_FOUND->HumanIdentityFailure.HUMAN_AUTH_CHALLENGE_NOT_FOUND;HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_EXPIRED->HumanIdentityFailure.CHALLENGE_EXPIRED;HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_CONSUMED->HumanIdentityFailure.CHALLENGE_CONSUMED;HumanAuthErrorCode.HUMAN_AUTH_CREDENTIAL_REJECTED->HumanIdentityFailure.CREDENTIAL_REJECTED;HumanAuthErrorCode.HUMAN_AUTH_NONCE_MISMATCH->HumanIdentityFailure.NONCE_MISMATCH;HumanAuthErrorCode.HUMAN_AUTH_PROVIDER_UNAVAILABLE->HumanIdentityFailure.PROVIDER_UNAVAILABLE;HumanAuthErrorCode.UNEXPECTED_RESPONSE->HumanIdentityFailure.UNEXPECTED_RESPONSE}
}
