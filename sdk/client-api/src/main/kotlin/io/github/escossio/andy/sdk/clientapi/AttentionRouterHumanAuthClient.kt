package io.github.escossio.andy.sdk.clientapi

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class GoogleChallenge(val challengeId:String,val nonce:String,val expiresAt:String)
data class HumanIdentityValidated(val humanIdentityId:String)
enum class HumanAuthErrorCode { NETWORK_FAILURE, HUMAN_AUTH_DISABLED, HUMAN_AUTH_CHALLENGE_NOT_FOUND, HUMAN_AUTH_CHALLENGE_EXPIRED, HUMAN_AUTH_CHALLENGE_CONSUMED, HUMAN_AUTH_CREDENTIAL_REJECTED, HUMAN_AUTH_NONCE_MISMATCH, HUMAN_AUTH_PROVIDER_UNAVAILABLE, UNEXPECTED_RESPONSE }
sealed interface ChallengeResult { data class Success(val challenge:GoogleChallenge):ChallengeResult; data class Failure(val error:HumanAuthErrorCode):ChallengeResult }
sealed interface VerifyResult { data class Success(val validated:HumanIdentityValidated):VerifyResult; data class Failure(val error:HumanAuthErrorCode):VerifyResult }
data class TransportResponse(val statusCode:Int,val body:String?)
interface HumanAuthTransport { fun post(path:String,body:String):TransportResponse }
interface HumanAuthClient { fun requestChallenge():ChallengeResult; fun verify(challengeId:String,idToken:String):VerifyResult }

class AttentionRouterHumanAuthClient(private val baseUrl:String,private val transport:HumanAuthTransport=OkHttpHumanAuthTransport(baseUrl)):HumanAuthClient {
 override fun requestChallenge()=try { val r=transport.post(PATH,"{}");if(r.statusCode==201) r.body?.let(::challenge)?:ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE) else ChallengeResult.Failure(error(r.body)) } catch(_:Exception){ChallengeResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE)}
 override fun verify(challengeId:String,idToken:String):VerifyResult {
  if(challengeId.isBlank()||idToken.isBlank()) return VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE)
  return try { val r=transport.post("$PATH/$challengeId/verify",buildJsonObject{put("id_token",idToken)}.toString());if(r.statusCode==200) r.body?.let(::validated)?:VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE) else VerifyResult.Failure(error(r.body)) } catch(_:Exception){VerifyResult.Failure(HumanAuthErrorCode.NETWORK_FAILURE)}
 }
 private fun challenge(body:String):ChallengeResult { val o=obj(body)?:return ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE);if(o.keys!=setOf("challenge_id","nonce","expires_at"))return ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE);val a=o.str("challenge_id");val b=o.str("nonce");val c=o.str("expires_at");return if(a==null||b==null||c==null)ChallengeResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE) else ChallengeResult.Success(GoogleChallenge(a,b,c)) }
 private fun validated(body:String):VerifyResult { val o=obj(body)?:return VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE);val id=o.str("human_identity_id");return if(o.keys==setOf("status","human_identity_id")&&o.str("status")=="HUMAN_IDENTITY_VALIDATED"&&id!=null)VerifyResult.Success(HumanIdentityValidated(id)) else VerifyResult.Failure(HumanAuthErrorCode.UNEXPECTED_RESPONSE) }
 private fun error(body:String?)=when(obj(body)?.str("code")){"HUMAN_AUTH_DISABLED"->HumanAuthErrorCode.HUMAN_AUTH_DISABLED;"HUMAN_AUTH_CHALLENGE_NOT_FOUND"->HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_NOT_FOUND;"HUMAN_AUTH_CHALLENGE_EXPIRED"->HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_EXPIRED;"HUMAN_AUTH_CHALLENGE_CONSUMED"->HumanAuthErrorCode.HUMAN_AUTH_CHALLENGE_CONSUMED;"HUMAN_AUTH_CREDENTIAL_REJECTED"->HumanAuthErrorCode.HUMAN_AUTH_CREDENTIAL_REJECTED;"HUMAN_AUTH_NONCE_MISMATCH"->HumanAuthErrorCode.HUMAN_AUTH_NONCE_MISMATCH;"HUMAN_AUTH_PROVIDER_UNAVAILABLE"->HumanAuthErrorCode.HUMAN_AUTH_PROVIDER_UNAVAILABLE;else->HumanAuthErrorCode.UNEXPECTED_RESPONSE}
 private fun obj(body:String?)=try{body?.takeIf{it.isNotBlank()}?.let{Json.parseToJsonElement(it).jsonObject}}catch(_:Exception){null}
 private fun JsonObject.str(key:String)=(this[key] as? JsonPrimitive)?.contentOrNull?.takeIf{it.isNotBlank()}
 private companion object { const val PATH="/api/v1/auth/google/challenges" }
}
class OkHttpHumanAuthTransport(private val baseUrl:String,private val http:OkHttpClient=OkHttpClient()):HumanAuthTransport {
 override fun post(path:String,body:String):TransportResponse { require(baseUrl.startsWith("https://")||baseUrl.startsWith("http://"));val request=Request.Builder().url(baseUrl.trimEnd('/')+path).post(body.toRequestBody("application/json".toMediaType())).build();http.newCall(request).execute{return TransportResponse(it.code,it.body?.string())} }
}
