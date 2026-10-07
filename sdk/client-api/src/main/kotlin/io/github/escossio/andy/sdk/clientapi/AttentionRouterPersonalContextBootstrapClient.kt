package io.github.escossio.andy.sdk.clientapi

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class PersonalContextBootstrapChat(
    val index: Int,
    val displayName: String,
    val threadType: String,
)

data class PersonalContextBootstrapSelection(
    val selectionId: String,
    val expiresAt: Instant,
    val chats: List<PersonalContextBootstrapChat>,
)

data class PersonalContextBootstrapRun(
    val runId: String,
    val state: String,
)

enum class PersonalContextBootstrapError {
    SESSION_REQUIRED,
    CLIENT_SESSION_UNAUTHENTICATED,
    CLIENT_SESSION_AUTHORITY_REJECTED,
    CLIENT_SESSION_TENANT_FORBIDDEN,
    CLIENT_SESSION_DEVICE_REJECTED,
    CLIENT_SESSION_ACTIVE_TENANT_REQUIRED,
    PERSONAL_CONTEXT_BOOTSTRAP_TENANT_FORBIDDEN,
    PERSONAL_CONTEXT_BOOTSTRAP_DISABLED,
    BOOTSTRAP_SELECTION_NOT_FOUND,
    BOOTSTRAP_SELECTION_EXPIRED,
    BOOTSTRAP_SELECTION_CONFLICT,
    PERSONAL_CONTEXT_BOOTSTRAP_SELECTION_INVALID,
    WHATSAPP_HISTORY_UNAVAILABLE,
    NETWORK_FAILURE,
    UNEXPECTED_RESPONSE,
}

sealed interface PersonalContextBootstrapSelectionResult {
    data class Success(val selection: PersonalContextBootstrapSelection?) :
        PersonalContextBootstrapSelectionResult
    data class Failure(val error: PersonalContextBootstrapError) :
        PersonalContextBootstrapSelectionResult
}

sealed interface PersonalContextBootstrapConfirmResult {
    data class Success(val run: PersonalContextBootstrapRun) :
        PersonalContextBootstrapConfirmResult
    data class Failure(val error: PersonalContextBootstrapError) :
        PersonalContextBootstrapConfirmResult
}

interface PersonalContextBootstrapClient {
    suspend fun pending(
        session: ClientSessionCredential?,
    ): PersonalContextBootstrapSelectionResult

    suspend fun confirm(
        session: ClientSessionCredential?,
        selectionId: String,
    ): PersonalContextBootstrapConfirmResult
}

private const val BASE_PATH = "/api/v1/personal-context/bootstrap/selections"
private const val CONSENT_REF =
    "owner-explicit-whatsapp-bootstrap-20261007-selected-4-13-17-23-25"
private const val MAX_BODY_BYTES = 16 * 1024
private val SELECTION_ID = Regex("^pbs_[A-Za-z0-9_-]{32}$")
private val RUN_STATES = setOf(
    "CREATED", "QUEUED", "RUNNING", "PAUSED", "COMPLETED", "CANCELLED", "FAILED",
)

private fun bootstrapHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(25, TimeUnit.SECONDS)
    .build()

class AttentionRouterPersonalContextBootstrapClient(
    baseUrl: String,
    private val http: OkHttpClient = bootstrapHttpClient(),
) : PersonalContextBootstrapClient {
    private val root = baseUrl.trimEnd('/')

    override suspend fun pending(
        session: ClientSessionCredential?,
    ): PersonalContextBootstrapSelectionResult {
        if (session == null) {
            return PersonalContextBootstrapSelectionResult.Failure(
                PersonalContextBootstrapError.SESSION_REQUIRED,
            )
        }
        return try {
            session.withToken { token ->
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$root$BASE_PATH/pending")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    http.newCall(request).execute().use { response ->
                        val body = response.boundedBody()
                        if (response.isSuccessful) parsePending(body)
                        else PersonalContextBootstrapSelectionResult.Failure(
                            parseError(response.code, body),
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PersonalContextBootstrapSelectionResult.Failure(
                PersonalContextBootstrapError.NETWORK_FAILURE,
            )
        }
    }

    override suspend fun confirm(
        session: ClientSessionCredential?,
        selectionId: String,
    ): PersonalContextBootstrapConfirmResult {
        if (session == null) {
            return PersonalContextBootstrapConfirmResult.Failure(
                PersonalContextBootstrapError.SESSION_REQUIRED,
            )
        }
        if (!SELECTION_ID.matches(selectionId)) {
            return PersonalContextBootstrapConfirmResult.Failure(
                PersonalContextBootstrapError.BOOTSTRAP_SELECTION_NOT_FOUND,
            )
        }
        return try {
            session.withToken { token ->
                withContext(Dispatchers.IO) {
                    val body = buildJsonObject {
                        put("consent_ref", CONSENT_REF)
                        put("processing_budget", buildJsonObject {
                            put("page_size", 50)
                            put("max_messages_per_chat", 1000)
                            put("max_total_messages", 5000)
                        })
                    }.toString()
                    val request = Request.Builder()
                        .url("$root$BASE_PATH/$selectionId/confirm")
                        .header("Authorization", "Bearer $token")
                        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    http.newCall(request).execute().use { response ->
                        val responseBody = response.boundedBody()
                        if (response.isSuccessful) parseConfirmed(responseBody)
                        else PersonalContextBootstrapConfirmResult.Failure(
                            parseError(response.code, responseBody),
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PersonalContextBootstrapConfirmResult.Failure(
                PersonalContextBootstrapError.NETWORK_FAILURE,
            )
        }
    }

    private fun parsePending(body: String?): PersonalContextBootstrapSelectionResult {
        val root = parseObject(body) ?: return unexpectedSelection()
        if (root.keys != setOf("contract_version", "selection") ||
            root.string("contract_version") != "1"
        ) return unexpectedSelection()
        if (root["selection"] == JsonNull) {
            return PersonalContextBootstrapSelectionResult.Success(null)
        }
        val value = root["selection"] as? JsonObject ?: return unexpectedSelection()
        if (value.keys != setOf("selection_id", "expires_at", "chats")) {
            return unexpectedSelection()
        }
        val id = value.string("selection_id") ?: return unexpectedSelection()
        val expiry = value.string("expires_at")?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        } ?: return unexpectedSelection()
        val array = value["chats"] as? JsonArray ?: return unexpectedSelection()
        if (!SELECTION_ID.matches(id) || array.size != 5) return unexpectedSelection()
        val chats = array.map { item ->
            val chat = item as? JsonObject ?: return unexpectedSelection()
            if (chat.keys != setOf("index", "display_name", "thread_type")) {
                return unexpectedSelection()
            }
            val index = chat["index"]?.let {
                (it as? JsonPrimitive)?.takeIf { part -> !part.isString }
                    ?.contentOrNull?.toIntOrNull()
            } ?: return unexpectedSelection()
            val name = chat.string("display_name") ?: return unexpectedSelection()
            val kind = chat.string("thread_type") ?: return unexpectedSelection()
            if (index !in 1..5000 || name.isBlank() || name.length > 160 ||
                name.contains('@') || Regex("\\d{6,}").containsMatchIn(name) ||
                kind !in setOf("DIRECT", "GROUP")
            ) return unexpectedSelection()
            PersonalContextBootstrapChat(index, name, kind)
        }
        if (chats.map { it.index }.toSet().size != 5 ||
            chats.count { it.threadType == "GROUP" } != 1
        ) return unexpectedSelection()
        return PersonalContextBootstrapSelectionResult.Success(
            PersonalContextBootstrapSelection(id, expiry, chats),
        )
    }

    private fun parseConfirmed(body: String?): PersonalContextBootstrapConfirmResult {
        val value = parseObject(body) ?: return unexpectedConfirm()
        if (value.keys != setOf("contract_version", "run_id", "source_kind", "state") ||
            value.string("contract_version") != "1" ||
            value.string("source_kind") != "WHATSAPP_TEXT"
        ) return unexpectedConfirm()
        val id = value.string("run_id") ?: return unexpectedConfirm()
        val state = value.string("state") ?: return unexpectedConfirm()
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id ||
            state !in RUN_STATES
        ) return unexpectedConfirm()
        return PersonalContextBootstrapConfirmResult.Success(
            PersonalContextBootstrapRun(id, state),
        )
    }

    private fun parseError(status: Int, body: String?): PersonalContextBootstrapError {
        val value = parseObject(body) ?: return PersonalContextBootstrapError.UNEXPECTED_RESPONSE
        if (value.keys != setOf("code")) return PersonalContextBootstrapError.UNEXPECTED_RESPONSE
        val code = value.string("code") ?: return PersonalContextBootstrapError.UNEXPECTED_RESPONSE
        val error = runCatching { PersonalContextBootstrapError.valueOf(code) }.getOrNull()
            ?: return PersonalContextBootstrapError.UNEXPECTED_RESPONSE
        val allowed = when (status) {
            401 -> setOf(PersonalContextBootstrapError.CLIENT_SESSION_UNAUTHENTICATED)
            403 -> setOf(
                PersonalContextBootstrapError.CLIENT_SESSION_AUTHORITY_REJECTED,
                PersonalContextBootstrapError.CLIENT_SESSION_TENANT_FORBIDDEN,
                PersonalContextBootstrapError.CLIENT_SESSION_DEVICE_REJECTED,
                PersonalContextBootstrapError.PERSONAL_CONTEXT_BOOTSTRAP_TENANT_FORBIDDEN,
            )
            404 -> setOf(PersonalContextBootstrapError.BOOTSTRAP_SELECTION_NOT_FOUND)
            409 -> setOf(
                PersonalContextBootstrapError.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED,
                PersonalContextBootstrapError.BOOTSTRAP_SELECTION_EXPIRED,
                PersonalContextBootstrapError.BOOTSTRAP_SELECTION_CONFLICT,
            )
            503 -> setOf(
                PersonalContextBootstrapError.PERSONAL_CONTEXT_BOOTSTRAP_DISABLED,
                PersonalContextBootstrapError.WHATSAPP_HISTORY_UNAVAILABLE,
            )
            else -> emptySet()
        }
        return if (error in allowed) error else PersonalContextBootstrapError.UNEXPECTED_RESPONSE
    }

    private fun parseObject(body: String?): JsonObject? = body?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun Response.boundedBody(): String? {
        val stream = body?.byteStream() ?: return null
        val bytes = ByteArray(MAX_BODY_BYTES + 1)
        var count = 0
        while (count < bytes.size) {
            val read = stream.read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read == 0) return null
            count += read
        }
        return if (count > MAX_BODY_BYTES) null else String(bytes, 0, count, Charsets.UTF_8)
    }

    private fun unexpectedSelection() = PersonalContextBootstrapSelectionResult.Failure(
        PersonalContextBootstrapError.UNEXPECTED_RESPONSE,
    )

    private fun unexpectedConfirm() = PersonalContextBootstrapConfirmResult.Failure(
        PersonalContextBootstrapError.UNEXPECTED_RESPONSE,
    )
}
