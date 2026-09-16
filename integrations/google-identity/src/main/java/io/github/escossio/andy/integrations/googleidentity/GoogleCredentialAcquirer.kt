package io.github.escossio.andy.integrations.googleidentity

import android.content.Context
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

sealed interface ProviderCredentialResult { data class Token(val idToken:String):ProviderCredentialResult; data object Cancelled:ProviderCredentialResult; data object Unavailable:ProviderCredentialResult }
interface GoogleCredentialAcquirer { suspend fun acquire(nonce:String):ProviderCredentialResult }
class AndroidGoogleCredentialAcquirer(private val context:Context,private val serverClientId:String,private val manager:CredentialManager=CredentialManager.create(context)):GoogleCredentialAcquirer {
 override suspend fun acquire(nonce:String):ProviderCredentialResult {
  if(serverClientId.isBlank()||nonce.isBlank())return ProviderCredentialResult.Unavailable
  val option=GetGoogleIdOption.Builder().setServerClientId(serverClientId).setNonce(nonce).setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false).build()
  return try { val credential=manager.getCredential(context,GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
   if(credential is CustomCredential&&credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) GoogleIdTokenCredential.createFrom(credential.data).idToken.takeIf{it.isNotBlank()}?.let(ProviderCredentialResult::Token)?:ProviderCredentialResult.Unavailable else ProviderCredentialResult.Unavailable
  } catch(_:GetCredentialCancellationException){ProviderCredentialResult.Cancelled} catch(_:GetCredentialException){ProviderCredentialResult.Unavailable} catch(_:Exception){ProviderCredentialResult.Unavailable}
 }
}
