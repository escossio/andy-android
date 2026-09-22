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

data class ClientCommand(
    val commandId: String,
    val clientRequestId: String,
    val modality: String,
    val inputText: String,
    val state: String,
    val normalizedAction: String?,
    val responseText: String?,
    val errorCode: String?,
    val createdAt: Instant,
    val processedAt: Instant?,
)

enum class ClientCommandErrorCode {
    NETWORK_FAILURE,
    CLIENT_COMMAND_DISABLED,
    CLIENT_COMMAND_AUTHORITY_REJECTED,
    CLIENT_COMMAND_CONFLICT,
    CLIENT_COMMAND_INVALID,
    CLIENT_COMMAND_UNAVAILABLE,
    CLIENT_SESSION_UNAUTHENTICATED,
    CLIENT_SESSION_AUTHORITY_REJECTED,
    CLIENT_SESSION_UNAVAILABLE,
    UNEXPECTED_RESPONSE,
}

sealed interface ClientCommandResult {
    data class Success(val command: ClientCommand) : ClientCommandResult
    data class Failure(val error: ClientCommandErrorCode) : ClientCommandResult
}

sealed interface ClientCommandListResult {
    data class Success(val commands: List<ClientCommand>) : ClientCommandListResult
    data class Failure(val error: ClientCommandErrorCode) : ClientCommandListResult
}

interface ClientCommandClient {
    suspend fun submitText(
        session: ClientSessionCredential,
        clientRequestId: String,
        text: String,
    ): ClientCommandResult

    suspend fun listRecent(
        session: ClientSessionCredential,
        limit: Int = 50,
    ): ClientCommandListResult
}

class AttentionRouterClientCommandClient(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) : ClientCommandClient {
    private val root = baseUrl.trimEnd('/')

    override suspend fun submitText(
        session: ClientSessionCredential,
        clientRequestId: String,
        text: String,
    ): ClientCommandResult {
        if (!validRequestId(clientRequestId) || text.isBlank() || text.length > 4000) {
            return ClientCommandResult.Failure(
                ClientCommandErrorCode.CLIENT_COMMAND_INVALID,
            )
        }
        return try {
            session.withToken { token ->
                withContext(Dispatchers.IO) {
                    val body = buildJsonObject {
                        put("client_request_id", clientRequestId)
                        put("text", text)
                    }.toString()
                    val request = Request.Builder()
                        .url("$root/api/v1/client/commands")
                        .header("Authorization", "Bearer $token")
                        .post(
                            body.toRequestBody(
                                "application/json; charset=utf-8".toMediaType(),
                            ),
                        )
                        .build()
                    http.newCall(request).execute().use { response ->
                        val raw = response.body?.string()
                        if (response.isSuccessful) parseCommandResult(raw)
                        else ClientCommandResult.Failure(parseError(raw))
                    }
                }
            }
        } catch (_: Exception) {
            ClientCommandResult.Failure(ClientCommandErrorCode.NETWORK_FAILURE)
        }
    }

    override suspend fun listRecent(
        session: ClientSessionCredential,
        limit: Int,
    ): ClientCommandListResult {
        if (limit !in 1..100) {
            return ClientCommandListResult.Failure(
                ClientCommandErrorCode.CLIENT_COMMAND_INVALID,
            )
        }
        return try {
            session.withToken { token ->
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$root/api/v1/client/commands?limit=$limit")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    http.newCall(request).execute().use { response ->
                        val raw = response.body?.string()
                        if (response.isSuccessful) parseList(raw)
                        else ClientCommandListResult.Failure(parseError(raw))
                    }
                }
            }
        } catch (_: Exception) {
            ClientCommandListResult.Failure(ClientCommandErrorCode.NETWORK_FAILURE)
        }
    }

    private fun parseCommandResult(body: String?): ClientCommandResult {
        val value = body?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        } ?: return unexpected()
        return parseCommand(value)
            ?.let(ClientCommandResult::Success)
            ?: unexpected()
    }

    private fun parseList(body: String?): ClientCommandListResult {
        val rootValue = body?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        } ?: return unexpectedList()
        if (rootValue.string("contract_version") != "1") return unexpectedList()
        val array = rootValue["commands"] as? JsonArray ?: return unexpectedList()
        if (array.size > 100) return unexpectedList()
        val commands = array.map { element ->
            parseCommand(element as? JsonObject ?: return unexpectedList())
                ?: return unexpectedList()
        }
        return ClientCommandListResult.Success(commands)
    }

    private fun parseCommand(value: JsonObject): ClientCommand? {
        val commandId = value.string("command_id") ?: return null
        val clientRequestId = value.string("client_request_id") ?: return null
        val modality = value.string("modality") ?: return null
        val inputText = value.string("input_text") ?: return null
        val state = value.string("state") ?: return null
        val createdAt = value.instant("created_at") ?: return null
        val processedAtValue = value["processed_at"]
        val processedAt = if (
            processedAtValue == null || processedAtValue.toString() == "null"
        ) {
            null
        } else {
            (processedAtValue as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return null
        }
        val normalizedAction = value.nullableString("normalized_action")
        val responseText = value.nullableString("response_text")
        val errorCode = value.nullableString("error_code")
        if (
            commandId.isBlank() || commandId.length > 64 ||
            !validRequestId(clientRequestId) ||
            modality !in MODALITIES ||
            inputText.isBlank() || inputText.length > 4000 ||
            state !in STATES ||
            normalizedAction?.length?.let { it > 80 } == true ||
            responseText?.length?.let { it > 4000 } == true ||
            errorCode?.length?.let { it > 120 } == true
        ) {
            return null
        }
        return ClientCommand(
            commandId = commandId,
            clientRequestId = clientRequestId,
            modality = modality,
            inputText = inputText,
            state = state,
            normalizedAction = normalizedAction,
            responseText = responseText,
            errorCode = errorCode,
            createdAt = createdAt,
            processedAt = processedAt,
        )
    }

    private fun parseError(body: String?): ClientCommandErrorCode {
        val code = body?.let {
            runCatching {
                Json.parseToJsonElement(it).jsonObject.string("code")
            }.getOrNull()
        }
        return runCatching { ClientCommandErrorCode.valueOf(code ?: "") }
            .getOrDefault(ClientCommandErrorCode.UNEXPECTED_RESPONSE)
    }

    private fun validRequestId(value: String): Boolean =
        value.length in 1..80 && value.all {
            it.isLetterOrDigit() || it in "_-.:"
        }

    private fun unexpected() =
        ClientCommandResult.Failure(ClientCommandErrorCode.UNEXPECTED_RESPONSE)

    private fun unexpectedList() =
        ClientCommandListResult.Failure(
            ClientCommandErrorCode.UNEXPECTED_RESPONSE,
        )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.nullableString(key: String): String? {
        val value = this[key] ?: return null
        if (value.toString() == "null") return null
        return (value as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.instant(key: String): Instant? =
        string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private companion object {
        val MODALITIES = setOf("TEXT", "VOICE")
        val STATES = setOf(
            "RECEIVED",
            "COMPLETED",
            "CLARIFICATION_REQUIRED",
            "GENERAL_TASK_PENDING",
            "FAILED",
        )
    }
}
