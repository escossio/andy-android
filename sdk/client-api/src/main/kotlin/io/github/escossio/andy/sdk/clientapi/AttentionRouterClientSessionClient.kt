package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

data class ClientSessionChallenge(
    val challengeId: String,
    val challengeB64Url: String,
    val expiresAt: Instant,
)

class ClientSessionCredential(
    token: String,
    val sessionId: String,
    val expiresAt: Instant,
    val humanIdentityId: String,
    val deviceId: String,
    val tenantId: String,
) {
    private val credential = token

    init {
        require(token.matches(SESSION_TOKEN))
        require(sessionId.matches(SESSION_ID))
        require(humanIdentityId.matches(HUMAN_ID))
        require(deviceId.matches(DEVICE_ID))
        require(tenantId.isNotBlank() && tenantId.length <= 64)
    }

    fun <T> withToken(block: (String) -> T): T = block(credential)

    override fun toString() =
        "ClientSessionCredential(sessionId=$sessionId, expiresAt=$expiresAt, " +
            "humanIdentityId=$humanIdentityId, deviceId=$deviceId, tenantId=$tenantId)"
}

data class AuthenticatedClientBootstrap(
    val humanIdentityId: String,
    val activeTenantId: String,
    val memberships: List<ClientTenantMembership>,
    val device: ClientDevice,
    val sessionExpiresAt: Instant,
    val serverTime: Instant,
)

enum class ClientSessionErrorCode {
    NETWORK_FAILURE,
    CLIENT_SESSION_DEVICE_REJECTED,
    CLIENT_SESSION_CHALLENGE_NOT_FOUND,
    CLIENT_SESSION_CHALLENGE_EXPIRED,
    CLIENT_SESSION_CHALLENGE_CONSUMED,
    CLIENT_SESSION_SIGNATURE_INVALID,
    CLIENT_SESSION_ACTIVE_TENANT_REQUIRED,
    CLIENT_SESSION_TENANT_FORBIDDEN,
    CLIENT_SESSION_UNAUTHENTICATED,
    CLIENT_SESSION_AUTHORITY_REJECTED,
    CLIENT_SESSION_UNAVAILABLE,
    UNEXPECTED_RESPONSE,
}

sealed interface ClientSessionChallengeResult {
    data class Success(val challenge: ClientSessionChallenge) : ClientSessionChallengeResult
    data class Failure(val error: ClientSessionErrorCode) : ClientSessionChallengeResult
}

sealed interface ClientSessionCompleteResult {
    data class Success(val session: ClientSessionCredential) : ClientSessionCompleteResult
    data class Failure(val error: ClientSessionErrorCode) : ClientSessionCompleteResult
}

sealed interface AuthenticatedBootstrapResult {
    data class Success(val bootstrap: AuthenticatedClientBootstrap) : AuthenticatedBootstrapResult
    data class Failure(val error: ClientSessionErrorCode) : AuthenticatedBootstrapResult
}

interface ClientSessionClient {
    suspend fun start(
        publicKeySpkiB64Url: String,
        requestedTenantId: String? = null,
    ): ClientSessionChallengeResult

    suspend fun complete(
        challengeId: String,
        signatureB64Url: String,
    ): ClientSessionCompleteResult

    suspend fun bootstrap(
        session: ClientSessionCredential,
    ): AuthenticatedBootstrapResult
}

data class ClientSessionTransportResponse(val statusCode: Int, val body: String?)

interface ClientSessionTransport {
    suspend fun post(path: String, body: String): ClientSessionTransportResponse
    suspend fun get(path: String, bearerToken: String): ClientSessionTransportResponse
}

class AttentionRouterClientSessionClient(
    baseUrl: String,
    private val transport: ClientSessionTransport = OkHttpClientSessionTransport(baseUrl),
) : ClientSessionClient {
    override suspend fun start(
        publicKeySpkiB64Url: String,
        requestedTenantId: String?,
    ): ClientSessionChallengeResult {
        if (
            !publicKeySpkiB64Url.matches(B64URL) ||
            publicKeySpkiB64Url.length !in 80..2048 ||
            (requestedTenantId != null &&
                (requestedTenantId.isBlank() || requestedTenantId.length > 64))
        ) {
            return ClientSessionChallengeResult.Failure(
                ClientSessionErrorCode.UNEXPECTED_RESPONSE,
            )
        }
        val body = buildJsonObject {
            put("public_key_spki_b64url", publicKeySpkiB64Url)
            requestedTenantId?.let { put("requested_tenant_id", it) }
        }.toString()
        return try {
            val response = transport.post(START_PATH, body)
            if (response.statusCode == 201) {
                parseChallenge(response.body)
            } else {
                ClientSessionChallengeResult.Failure(error(response.body))
            }
        } catch (_: Exception) {
            ClientSessionChallengeResult.Failure(ClientSessionErrorCode.NETWORK_FAILURE)
        }
    }

    override suspend fun complete(
        challengeId: String,
        signatureB64Url: String,
    ): ClientSessionCompleteResult {
        if (
            !challengeId.matches(CHALLENGE_ID) ||
            !signatureB64Url.matches(B64URL) ||
            signatureB64Url.length !in 64..512
        ) {
            return ClientSessionCompleteResult.Failure(
                ClientSessionErrorCode.UNEXPECTED_RESPONSE,
            )
        }
        return try {
            val body = buildJsonObject {
                put("device_signature_b64url", signatureB64Url)
            }.toString()
            val response = transport.post("$START_PATH/$challengeId/complete", body)
            if (response.statusCode == 200) {
                parseSession(response.body)
            } else {
                ClientSessionCompleteResult.Failure(error(response.body))
            }
        } catch (_: Exception) {
            ClientSessionCompleteResult.Failure(ClientSessionErrorCode.NETWORK_FAILURE)
        }
    }

    override suspend fun bootstrap(
        session: ClientSessionCredential,
    ): AuthenticatedBootstrapResult {
        return try {
            val response = session.withToken { token ->
                transport.get(BOOTSTRAP_PATH, token)
            }
            if (response.statusCode == 200) {
                parseBootstrap(response.body)
            } else {
                AuthenticatedBootstrapResult.Failure(error(response.body))
            }
        } catch (_: Exception) {
            AuthenticatedBootstrapResult.Failure(ClientSessionErrorCode.NETWORK_FAILURE)
        }
    }

    private fun parseChallenge(body: String?): ClientSessionChallengeResult {
        val value = obj(body) ?: return unexpectedChallenge()
        if (value.keys != setOf("session_challenge_id", "challenge_b64url", "expires_at")) {
            return unexpectedChallenge()
        }
        val challengeId = value.str("session_challenge_id")
            ?.takeIf { it.matches(CHALLENGE_ID) }
            ?: return unexpectedChallenge()
        val challenge = value.str("challenge_b64url")
            ?.takeIf { it.length in 32..256 && it.matches(B64URL) }
            ?: return unexpectedChallenge()
        val expiresAt = instant(value.str("expires_at")) ?: return unexpectedChallenge()
        return ClientSessionChallengeResult.Success(
            ClientSessionChallenge(challengeId, challenge, expiresAt),
        )
    }

    private fun parseSession(body: String?): ClientSessionCompleteResult {
        val value = obj(body) ?: return unexpectedComplete()
        if (
            value.keys != setOf("status", "session") ||
            value.str("status") != "CLIENT_SESSION_ESTABLISHED"
        ) {
            return unexpectedComplete()
        }
        val session = value["session"] as? JsonObject ?: return unexpectedComplete()
        if (
            session.keys != setOf(
                "session_id",
                "session_token",
                "token_type",
                "expires_at",
                "human_identity_id",
                "device_id",
                "tenant_id",
            ) ||
            session.str("token_type") != "Bearer"
        ) {
            return unexpectedComplete()
        }
        val sessionId = session.str("session_id")
            ?.takeIf { it.matches(SESSION_ID) }
            ?: return unexpectedComplete()
        val token = session.str("session_token")
            ?.takeIf { it.matches(SESSION_TOKEN) }
            ?: return unexpectedComplete()
        val expiresAt = instant(session.str("expires_at")) ?: return unexpectedComplete()
        val humanId = session.str("human_identity_id")
            ?.takeIf { it.matches(HUMAN_ID) }
            ?: return unexpectedComplete()
        val deviceId = session.str("device_id")
            ?.takeIf { it.matches(DEVICE_ID) }
            ?: return unexpectedComplete()
        val tenantId = session.str("tenant_id")
            ?.takeIf { it.length in 1..64 }
            ?: return unexpectedComplete()

        return ClientSessionCompleteResult.Success(
            ClientSessionCredential(
                token = token,
                sessionId = sessionId,
                expiresAt = expiresAt,
                humanIdentityId = humanId,
                deviceId = deviceId,
                tenantId = tenantId,
            ),
        )
    }

    private fun parseBootstrap(body: String?): AuthenticatedBootstrapResult {
        val value = obj(body) ?: return unexpectedBootstrap()
        if (
            value.keys != setOf(
                "contract_version",
                "human_identity_id",
                "active_tenant_id",
                "memberships",
                "device",
                "session_expires_at",
                "server_time",
            ) ||
            value.str("contract_version") != "1"
        ) {
            return unexpectedBootstrap()
        }

        val humanId = value.str("human_identity_id")
            ?.takeIf { it.matches(HUMAN_ID) }
            ?: return unexpectedBootstrap()
        val activeTenantId = value.str("active_tenant_id")
            ?.takeIf { it.length in 1..64 }
            ?: return unexpectedBootstrap()

        val membershipsValue = value["memberships"] as? JsonArray ?: return unexpectedBootstrap()
        if (membershipsValue.isEmpty() || membershipsValue.size > 100) {
            return unexpectedBootstrap()
        }
        val memberships = membershipsValue.map { element ->
            val membership = element as? JsonObject ?: return unexpectedBootstrap()
            if (membership.keys != setOf("membership_id", "tenant_id", "role", "status")) {
                return unexpectedBootstrap()
            }
            val membershipId = membership.str("membership_id")
                ?.takeIf { it.length in 1..64 }
                ?: return unexpectedBootstrap()
            val tenantId = membership.str("tenant_id")
                ?.takeIf { it.length in 1..64 }
                ?: return unexpectedBootstrap()
            val role = try {
                ClientTenantRole.valueOf(
                    membership.str("role") ?: return unexpectedBootstrap(),
                )
            } catch (_: Exception) {
                return unexpectedBootstrap()
            }
            if (membership.str("status") != "ACTIVE") return unexpectedBootstrap()
            ClientTenantMembership(membershipId, tenantId, role)
        }
        if (memberships.none { it.tenantId == activeTenantId }) return unexpectedBootstrap()

        val deviceValue = value["device"] as? JsonObject ?: return unexpectedBootstrap()
        val device = parseDevice(deviceValue) ?: return unexpectedBootstrap()
        val sessionExpiresAt = instant(value.str("session_expires_at"))
            ?: return unexpectedBootstrap()
        val serverTime = instant(value.str("server_time")) ?: return unexpectedBootstrap()

        return AuthenticatedBootstrapResult.Success(
            AuthenticatedClientBootstrap(
                humanIdentityId = humanId,
                activeTenantId = activeTenantId,
                memberships = memberships,
                device = device,
                sessionExpiresAt = sessionExpiresAt,
                serverTime = serverTime,
            ),
        )
    }

    private fun parseDevice(value: JsonObject): ClientDevice? {
        if (
            value.keys != setOf(
                "device_id",
                "public_key_fingerprint",
                "canonical_name",
                "platform",
                "roles",
                "status",
            ) ||
            value.str("platform") != "ANDROID" ||
            value.str("status") != "ACTIVE"
        ) {
            return null
        }
        val deviceId = value.str("device_id")
            ?.takeIf { it.matches(DEVICE_ID) }
            ?: return null
        val fingerprint = value.str("public_key_fingerprint")
            ?.takeIf { it.matches(FINGERPRINT) }
            ?: return null
        val canonicalName = value.str("canonical_name")
            ?.takeIf { it.length in 1..160 }
            ?: return null
        val roleValues = value["roles"] as? JsonArray ?: return null
        if (roleValues.isEmpty() || roleValues.size > 2) return null
        val roles = roleValues.map { element ->
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            try {
                DeviceBootstrapRole.valueOf(
                    primitive.contentOrNull ?: return null,
                )
            } catch (_: Exception) {
                return null
            }
        }.toSet()
        if (roles.size != roleValues.size) return null

        return ClientDevice(
            deviceId = deviceId,
            publicKeyFingerprint = fingerprint,
            canonicalName = canonicalName,
            roles = roles,
        )
    }

    private fun error(body: String?) = when (obj(body)?.str("code")) {
        "CLIENT_SESSION_DEVICE_REJECTED" ->
            ClientSessionErrorCode.CLIENT_SESSION_DEVICE_REJECTED
        "CLIENT_SESSION_CHALLENGE_NOT_FOUND" ->
            ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_NOT_FOUND
        "CLIENT_SESSION_CHALLENGE_EXPIRED" ->
            ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_EXPIRED
        "CLIENT_SESSION_CHALLENGE_CONSUMED" ->
            ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_CONSUMED
        "CLIENT_SESSION_SIGNATURE_INVALID" ->
            ClientSessionErrorCode.CLIENT_SESSION_SIGNATURE_INVALID
        "CLIENT_SESSION_ACTIVE_TENANT_REQUIRED" ->
            ClientSessionErrorCode.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED
        "CLIENT_SESSION_TENANT_FORBIDDEN" ->
            ClientSessionErrorCode.CLIENT_SESSION_TENANT_FORBIDDEN
        "CLIENT_SESSION_UNAUTHENTICATED" ->
            ClientSessionErrorCode.CLIENT_SESSION_UNAUTHENTICATED
        "CLIENT_SESSION_AUTHORITY_REJECTED" ->
            ClientSessionErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED
        "CLIENT_SESSION_UNAVAILABLE" ->
            ClientSessionErrorCode.CLIENT_SESSION_UNAVAILABLE
        else -> ClientSessionErrorCode.UNEXPECTED_RESPONSE
    }

    private fun unexpectedChallenge() = ClientSessionChallengeResult.Failure(
        ClientSessionErrorCode.UNEXPECTED_RESPONSE,
    )

    private fun unexpectedComplete() = ClientSessionCompleteResult.Failure(
        ClientSessionErrorCode.UNEXPECTED_RESPONSE,
    )

    private fun unexpectedBootstrap() = AuthenticatedBootstrapResult.Failure(
        ClientSessionErrorCode.UNEXPECTED_RESPONSE,
    )

    private fun obj(body: String?) = try {
        body
            ?.takeIf { it.isNotBlank() }
            ?.let { Json.parseToJsonElement(it).jsonObject }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(key: String) =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun instant(value: String?) = try {
        value?.let(Instant::parse)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val START_PATH = "/api/v1/session/device/challenges"
        const val BOOTSTRAP_PATH = "/api/v1/client/bootstrap"
        val B64URL = Regex("^[A-Za-z0-9_-]+$")
        val CHALLENGE_ID = Regex("^csc_[A-Za-z0-9_-]{20,}$")
        val SESSION_ID = Regex("^csn_[A-Za-z0-9_-]{20,}$")
        val SESSION_TOKEN = Regex("^cst_[A-Za-z0-9_-]{43}$")
        val HUMAN_ID = Regex("^hid_[A-Za-z0-9_-]{20,}$")
        val DEVICE_ID = Regex("^cdev_[A-Za-z0-9_-]{20,}$")
        val FINGERPRINT = Regex("^sha256:[0-9a-f]{64}$")
    }
}

class OkHttpClientSessionTransport(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) : ClientSessionTransport {
    override suspend fun post(
        path: String,
        body: String,
    ): ClientSessionTransportResponse {
        require(baseUrl.startsWith("https://"))
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return execute(request)
    }

    override suspend fun get(
        path: String,
        bearerToken: String,
    ): ClientSessionTransportResponse {
        require(baseUrl.startsWith("https://"))
        require(bearerToken.matches(SESSION_TOKEN))
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $bearerToken")
            .get()
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request) = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            ClientSessionTransportResponse(response.code, response.body?.string())
        }
    }

    private companion object {
        val SESSION_TOKEN = Regex("^cst_[A-Za-z0-9_-]{43}$")
    }
}
