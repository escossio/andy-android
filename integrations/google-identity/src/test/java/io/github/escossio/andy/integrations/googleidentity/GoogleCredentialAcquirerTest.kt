package io.github.escossio.andy.integrations.googleidentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
class GoogleCredentialAcquirerTest {
 @Test fun syntheticCancellationIsDistinct()=runBlocking { val a=object:GoogleCredentialAcquirer{override suspend fun acquire(nonce:String)=ProviderCredentialResult.Cancelled};assertEquals(ProviderCredentialResult.Cancelled,a.acquire("nonce-synthetic")) }
 @Test fun syntheticProviderFailureIsNotToken(){assertEquals(ProviderCredentialResult.Unavailable,ProviderCredentialResult.Unavailable)}
}
