package io.github.escossio.andy.sdk.clientapi

import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.escossio.andy.core.humanidentity.HumanIdentityReference
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class GoogleChallenge(val challengeId:String,val nonce:String,val expiresAt:String)
data class HumanIdentityValidated(val identityReference:HumanIdentityReference)
enum class HumanAuthContinuationPurpose { DEVICE_BOOTSTRAP }
class HumanAuthContinuationGrant(token:String,val purpose:HumanAuthContinuationPurpose,val expiresAt:Instant) {
 private val credential=token
 init { require(token.matches(Regex("^hcg_[A-Za-z0-9_-]{43}$"))) }
 fun <T> withToken(block:(String)->T):T=block(credential)
 override fun toString()="HumanAuthContinuationGrant(purpose=$purpose, expiresAt=$expiresAt)"
}
data class HumanIdentityContinuation(val identityReference:HumanIdentityReference,val grant:HumanAuthContinuationGrant)
enum class HumanAuthErrorCode { NETWORK_FAILURE, HUMAN_AUTH_DISABLED, HUMAN_AUTH_CHALLENGE_NOT_FOUND, HUMAN_AUTH_CHALLENGE_EXPIRED, HUMAN_AUTH_CHALLENGE_CONSUMED, HUMAN_AUTH_CREDENTIAL_REJECTED, HUMAN_AUTH_NONCE_MISMATCH, HUMAN_AUTH_PROVIDER_UNAVAILABLE, UNEXPECTED_RESPONSE }
sealed interface ChallengeResult { data class Success(val challenge:GoogleChallenge):ChallengeResult; data class Failure(val error:HumanAuthErrorCode):ChallengeResult }
sealed interface VerifyResult { data class Success(val validated:HumanIdentityValidated):VerifyResult; data class Failure(val error:HumanAuthErrorCode):VerifyResult }
sealed interface ContinuationResult { data class Success(val continuation:HumanIdentityContinuation):ContinuationResult; data class Failure(val error:HumanAuthErrorCode):ContinuationResult }
data class TransportResponse(val statusCode:Int,val body:String?)
interface HumanAuthTransport { suspend fun post(path:String,body:String):TransportResponse }
interface HumanAuthClient { suspend fun requestChallenge():ChallengeResult; suspend fun verify(challengeId:String,idToken:String):VerifyResult; suspend fun verifyAndContinue(challengeId:String,idToken:String):ContinuationResult }

class AttentionRouterHumanAuthClient(private val baseUrl:String,private val transport:HumanAuthTransport=OkHttpHumanAuthTransport(baseUrl)):HumanAuthClient {
 override suspend fun requestChallenge()=try { val r=transport.post(PATH,"{}");if(r.statusCode==201) r.body?.let(::challenge)?:ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE) else ChallengeResult.Failure(error(r.body)) } catch(_:Exception){ChallengeResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE)}
 override suspend fun verify(challengeId:String,idToken:String):VerifyResult {
  if(challengeId.isBlank()||idToken.isBlank()) return VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE)
  return try { val r=transport.post("$PATH/$challengeId/verify",buildJsonObject{put("id_token",idToken)}.toString());if(r.statusCode==200) r.body?.let(::validated)?:VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE) else VerifyResult.Failure(error(r.body)) } catch(_:Exception){VerifyResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE)}
 }
 override suspend fun verifyAndContinue(challengeId:String,idToken:String):ContinuationResult {
  if(challengeId.isBlank()||idToken.isBlank()) return ContinuationResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE)
  return try { val r=transport.post("$PATH/$challengeId/verify-and-continue",buildJsonObject{put("id_token",idToken)}.toString());if(r.statusCode==200) continued(r.body) else ContinuationResult.Failure(error(r.body)) } catch(_:Exception){ContinuationResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE)}
 }
 private fun challenge(body:String):ChallengeResult { val o=obj(body)?:return ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE);if(o.keys!=setOf("challenge_id","nonce","expires_at"))return ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE);val a=o.str("challenge_id");val b=o.str("nonce");val c=o.str("expires_at");return if(a==null||b==null||c==null)ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE) else ChallengeResult.Success(GoogleChallenge(a,b,c)) }
 private fun validated(body:String):VerifyResult { val o=obj(body)?:return VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE);val id=o.str("human_identity_id");return if(o.keys==setOf("status","human_identity_id")&&o.str("status")=="HUMAN_IDENTITY_VALIDATED"&&id!=null)VerifyResult.Success(HumanIdentityValidated(HumanIdentityReference(id))) else VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE) }
 private fun continued(body:String?):ContinuationResult {
  val failure=ContinuationResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE)
  val o=obj(body)?:return failure
  if(o.keys!=setOf("status","human_identity_id","continuation_grant")||o.str("status")!="HUMAN_IDENTITY_VALIDATED")return failure
  val id=o.str("human_identity_id")?.takeIf{it.matches(Regex("^hid_[A-Za-z0-9_-]{20,}$"))}?:return failure
  val grant=o["continuation_grant"] as? JsonObject?:return failure
  if(grant.keys!=setOf("token","purpose","expires_at"))return failure
  val token=grant.str("token")?.takeIf{it.matches(Regex("^hcg_[A-Za-z0-9_-]{43}$"))}?:return failure
  val purpose=grant.str("purpose")?.takeIf{it=="DEVICE_BOOTSTRAP"}?.let{HumanAuthContinuationPurpose.DEVICE_BOOTSTRAP}?:return failure
  val expiresAt=try{Instant.parse(grant.str("expires_at")?:return failure)}catch(_:Exception){return failure}
  return ContinuationResult.Success(HumanIdentityContinuation(HumanIdentityReference(id),HumanAuthContinuationGrant(token,purpose,expiresAt)))
 }
 private fun error(body:String?)=when(obj(body)?.str("code")){"HUMAN_AUTH_DISABLED"->HumanAuthErrorCode.HUMAN_AUTH_DISABLED;"HUMAN_AUTH_CHALLENGE_NOT_FOUND"->HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_NOT_FOUND;"HUMAN_AUTH_CHALLENGE_EXPIRED"->HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_EXPIRED;"HUMAN_AUTH_CHALLENGE_CONSUMED"->HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_CONSUMED;"HUMAN_AUTH_CREDENTIAL_REJECTED"->HumanAuthErrorCode.HUMAN_AUTH_CREDENTIAL_REJECTED;"HUMAN_AUTH_NONCE_MISMATCH"->HumanAuthErrorCode.HUMAN_AUTH_NONCE_MISMATCH;"HUMAN_AUTH_PROVIDER_UNAVAILABLE"->HumanAuthErrorCode.HUMAN_AUTH_PROVIDER_UNAVAILABLE;else->HumanAuthErrorCode.UNEXPECTED_RESPONSE}
 private fun obj(body:String?)=try{body?.takeIf{it.isNotBlank()}?.let{Json.parseToJsonElement(it).jsonObject}}catch(_:Exception){null}
 private fun JsonObject.str(key:String)=(this[key] as? JsonPrimitive)?.contentOrNull?.takeIf{it.isNotBlank()}
 private companion object { const val PATH="/api/v1/auth/google/challenges" }
}
class OkHttpHumanAuthTransport(private val baseUrl:String,private val http:OkHttpClient=OkHttpClient()):HumanAuthTransport {
 override suspend fun post(path:String,body:String):TransportResponse { require(baseUrl.startsWith("https://"));val request=Request.Builder().url(baseUrl.trimEnd('/')+path).post(body.toRequestBody("application/json".toMediaType())).build();return withContext(Dispatchers.IO) { http.newCall(request).execute().use { response -> TransportResponse(response.code,response.body?.string()) } } }
}
