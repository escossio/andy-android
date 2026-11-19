package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val CONTACTS_READONLY_SCOPE = "https://www.googleapis.com/auth/contacts.readonly"
sealed interface ContactsResult {
    class Success(val payload: JsonObject) : ContactsResult {
        val status: String? get() = (payload["status"] as? JsonPrimitive)?.contentOrNull
        val syncStatus: String? get() = (payload["sync_status"] as? JsonPrimitive)?.contentOrNull
        companion object {
            fun state(status: String, sync: String? = null) = Success(buildJsonObject {
                put("status", status); if (sync != null) put("sync_status", sync)
            })
        }
    }
    data class Failure(val code: String) : ContactsResult
}
interface ContactsClient {
    suspend fun status(session: ClientSessionCredential): ContactsResult
    suspend fun connect(session: ClientSessionCredential, code: String): ContactsResult
    suspend fun disconnect(session: ClientSessionCredential): ContactsResult
    suspend fun sync(session: ClientSessionCredential): ContactsResult
}

/** Provider codes are forwarded once through the existing authenticated HTTPS transport. */
class AttentionRouterContactsClient(
    baseUrl: String,
    private val transport: GmailConnectionTransport = OkHttpGmailConnectionTransport(baseUrl),
) : ContactsClient {
    override suspend fun status(session: ClientSessionCredential) = execute(session) { transport.get(PATH, it) }
    override suspend fun connect(session: ClientSessionCredential, code: String): ContactsResult {
        if (code.length !in 8..8192 || code.isBlank()) return ContactsResult.Failure("INVALID_CODE")
        return execute(session) { transport.post(PATH, it, buildJsonObject {
            put("authorization_code", code); put("consent", true)
        }.toString()) }
    }
    override suspend fun disconnect(session: ClientSessionCredential) = execute(session) { transport.delete(PATH, it) }
    override suspend fun sync(session: ClientSessionCredential) = execute(session) { transport.post("$PATH/sync", it, "{}") }
    suspend fun search(session: ClientSessionCredential, query: String, kind: String = "name"): ContactsResult {
        if (query.isBlank() || query.length > 240 || kind !in setOf("name", "phone", "email")) return ContactsResult.Failure("INVALID_QUERY")
        return execute(session) { transport.post("$PATH/search", it, buildJsonObject {
            put("query", query); put("kind", kind); put("limit", 30)
        }.toString()) }
    }
    private suspend fun execute(session: ClientSessionCredential, request: suspend (String) -> GmailConnectionTransportResponse): ContactsResult = try {
        val response = session.withToken(request)
        if (response.statusCode != 200) ContactsResult.Failure("CONTACTS_REQUEST_REJECTED")
        else {
            val payload = Json.parseToJsonElement(response.body ?: "").jsonObject
            val status = (payload["status"] as? JsonPrimitive)?.contentOrNull
            val scope = (payload["scope"] as? JsonPrimitive)?.contentOrNull
            if (status !in setOf("ACTIVE", "CONNECTED", "DISCONNECTED", "AUTH_REQUIRED", "SCHEDULED", "OK", "INCOMPLETE", "INVALID_PHONE") ||
                (status in setOf("ACTIVE", "CONNECTED") && scope != CONTACTS_READONLY_SCOPE)) ContactsResult.Failure("UNEXPECTED_RESPONSE")
            else ContactsResult.Success(payload)
        }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { ContactsResult.Failure("CONTACTS_UNAVAILABLE") }
    private companion object { const val PATH = "/api/v1/integrations/contacts" }
}
