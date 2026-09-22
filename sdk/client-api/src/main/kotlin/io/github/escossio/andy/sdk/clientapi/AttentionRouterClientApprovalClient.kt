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

data class ClientApproval(
    val approvalId: String,
    val state: String,
    val capability: String,
    val operation: String,
    val target: String,
    val preview: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

enum class ClientApprovalDecision {
    APPROVE,
    DENY,
}

enum class ClientApprovalErrorCode {
    NETWORK_FAILURE,
    CLIENT_APPROVAL_DISABLED,
    CLIENT_APPROVAL_NOT_FOUND,
    CLIENT_APPROVAL_AUTHORITY_REJECTED,
    CLIENT_APPROVAL_CONFLICT,
    CLIENT_APPROVAL_EXPIRED,
    CLIENT_APPROVAL_UNAVAILABLE,
    CLIENT_SESSION_UNAUTHENTICATED,
    CLIENT_SESSION_AUTHORITY_REJECTED,
    CLIENT_SESSION_UNAVAILABLE,
    INVALID_APPROVAL_ID,
    UNEXPECTED_RESPONSE,
}

sealed interface ClientApprovalListResult {
    data class Success(val approvals: List<ClientApproval>) : ClientApprovalListResult
    data class Failure(val error: ClientApprovalErrorCode) : ClientApprovalListResult
}

sealed interface ClientApprovalResult {
    data class Success(val approval: ClientApproval) : ClientApprovalResult
    data class Failure(val error: ClientApprovalErrorCode) : ClientApprovalResult
}

interface ClientApprovalClient {
    suspend fun listPending(session: ClientSessionCredential): ClientApprovalListResult
    suspend fun get(
        session: ClientSessionCredential,
        approvalId: String,
    ): ClientApprovalResult
    suspend fun decide(
        session: ClientSessionCredential,
        approvalId: String,
        decision: ClientApprovalDecision,
    ): ClientApprovalResult
}
class AttentionRouterClientApprovalClient(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) : ClientApprovalClient {
    private val root = baseUrl.trimEnd('/')

    override suspend fun listPending(
        session: ClientSessionCredential,
    ): ClientApprovalListResult = try {
        session.withToken { token ->
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$root/api/v1/client/approvals/pending")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string()
                    if (response.isSuccessful) parseList(text)
                    else ClientApprovalListResult.Failure(parseError(text))
                }
            }
        }
    } catch (_: Exception) {
        ClientApprovalListResult.Failure(ClientApprovalErrorCode.NETWORK_FAILURE)
    }

    override suspend fun get(
        session: ClientSessionCredential,
        approvalId: String,
    ): ClientApprovalResult {
        if (!validApprovalId(approvalId)) {
            return ClientApprovalResult.Failure(
                ClientApprovalErrorCode.INVALID_APPROVAL_ID,
            )
        }
        return requestApproval(
            session = session,
            approvalId = approvalId,
            decision = null,
        )
    }

    override suspend fun decide(
        session: ClientSessionCredential,
        approvalId: String,
        decision: ClientApprovalDecision,
    ): ClientApprovalResult {
        if (!validApprovalId(approvalId)) {
            return ClientApprovalResult.Failure(
                ClientApprovalErrorCode.INVALID_APPROVAL_ID,
            )
        }
        return requestApproval(
            session = session,
            approvalId = approvalId,
            decision = decision,
        )
    }
    private suspend fun requestApproval(
        session: ClientSessionCredential,
        approvalId: String,
        decision: ClientApprovalDecision?,
    ): ClientApprovalResult = try {
        session.withToken { token ->
            withContext(Dispatchers.IO) {
                val suffix = if (decision == null) {
                    "/api/v1/client/approvals/$approvalId"
                } else {
                    "/api/v1/client/approvals/$approvalId/decision"
                }
                val builder = Request.Builder()
                    .url(root + suffix)
                    .header("Authorization", "Bearer $token")
                if (decision == null) {
                    builder.get()
                } else {
                    val body = buildJsonObject {
                        put("decision", decision.name)
                    }.toString()
                    builder.post(
                        body.toRequestBody(
                            "application/json; charset=utf-8".toMediaType(),
                        ),
                    )
                }
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string()
                    if (response.isSuccessful) parseApprovalResult(text)
                    else ClientApprovalResult.Failure(parseError(text))
                }
            }
        }
    } catch (_: Exception) {
        ClientApprovalResult.Failure(ClientApprovalErrorCode.NETWORK_FAILURE)
    }

    private fun parseList(body: String?): ClientApprovalListResult {
        val rootValue = body?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        } ?: return unexpectedList()
        if (rootValue.string("contract_version") != "1") return unexpectedList()
        val array = rootValue["approvals"] as? JsonArray ?: return unexpectedList()
        if (array.size > 50) return unexpectedList()
        val approvals = array.map { element ->
            parseApproval(element as? JsonObject ?: return unexpectedList())
                ?: return unexpectedList()
        }
        return ClientApprovalListResult.Success(approvals)
    }

    private fun parseApprovalResult(body: String?): ClientApprovalResult {
        val value = body?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        } ?: return unexpected()
        return parseApproval(value)
            ?.let(ClientApprovalResult::Success)
            ?: unexpected()
    }
    private fun parseApproval(value: JsonObject): ClientApproval? {
        val approvalId = value.string("approval_id") ?: return null
        val state = value.string("state") ?: return null
        val capability = value.string("capability") ?: return null
        val operation = value.string("operation") ?: return null
        val target = value.string("target") ?: return null
        val preview = value.string("preview") ?: return null
        val issuedAt = value.instant("issued_at") ?: return null
        val expiresAt = value.instant("expires_at") ?: return null
        if (
            !validApprovalId(approvalId) ||
            state !in STATES ||
            capability.isBlank() || capability.length > 120 ||
            operation.isBlank() || operation.length > 120 ||
            target.isBlank() || target.length > 180 ||
            preview.isBlank() || preview.length > 4000 ||
            !expiresAt.isAfter(issuedAt)
        ) {
            return null
        }
        return ClientApproval(
            approvalId = approvalId,
            state = state,
            capability = capability,
            operation = operation,
            target = target,
            preview = preview,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )
    }

    private fun parseError(body: String?): ClientApprovalErrorCode {
        val code = body?.let {
            runCatching {
                Json.parseToJsonElement(it).jsonObject.string("code")
            }.getOrNull()
        }
        return runCatching { ClientApprovalErrorCode.valueOf(code ?: "") }
            .getOrDefault(ClientApprovalErrorCode.UNEXPECTED_RESPONSE)
    }

    private fun validApprovalId(value: String): Boolean =
        value.length in 1..64 && value.all {
            it.isLetterOrDigit() || it in "_-.:"
        }

    private fun unexpected() =
        ClientApprovalResult.Failure(ClientApprovalErrorCode.UNEXPECTED_RESPONSE)

    private fun unexpectedList() =
        ClientApprovalListResult.Failure(
            ClientApprovalErrorCode.UNEXPECTED_RESPONSE,
        )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.instant(key: String): Instant? =
        string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private companion object {
        val STATES = setOf(
            "PENDING_HUMAN_APPROVAL",
            "APPROVED",
            "DENIED",
            "EXPIRED",
            "CONSUMED",
            "REVOKED",
        )
    }
}
