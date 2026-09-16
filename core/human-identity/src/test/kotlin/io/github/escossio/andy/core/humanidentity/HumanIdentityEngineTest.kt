package io.github.escossio.andy.core.humanidentity
import org.junit.Assert.*
import org.junit.Test
class HumanIdentityEngineTest {
 @Test fun onlyBackendConfirmationValidates() { val e=HumanIdentityEngine(true); e.begin();e.challengeReceived();e.providerCredentialReceived();assertEquals(HumanIdentityState.Validated("human-synthetic"),e.backendValidated("human-synthetic")) }
 @Test fun localCredentialCannotValidate() { val e=HumanIdentityEngine(true);e.begin();e.challengeReceived();assertFalse(e.providerCredentialReceived() is HumanIdentityState.Validated) }
 @Test fun cancelAndRetryFailClosed() { val e=HumanIdentityEngine(true);e.begin();e.fail(HumanIdentityFailure.PROVIDER_CANCELLED);e.retry();assertEquals(HumanIdentityState.Unauthenticated,e.state) }
 @Test fun unavailableDeviceDoesNotStart() { assertEquals(HumanIdentityState.DeviceIdentityUnavailable,HumanIdentityEngine(false).begin()) }
}
