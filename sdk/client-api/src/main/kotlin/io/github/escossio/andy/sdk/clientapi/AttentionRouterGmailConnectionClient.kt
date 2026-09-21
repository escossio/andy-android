package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

const val GMAIL_METADATA_SCOPE = "https://www.googleapis.com/auth/gmail.metadata"
const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
private val GMAIL_SUPPORTED_SCOPE_PROFILES =
    setOf(GMAIL_METADATA_SCOPE, GMAIL_READONLY_SCOPE)

enum class GmailConnectionStatus {
    CONNECTED,
    DISCONNECTED,
}

data class GmailConnection(
    val status: GmailConnectionStatus,
    val grantedScopes: Set<String>,
)

enum class GmailConnectionErrorCode {
    NETWORK_FAILURE,
    GMAIL_CONNECTION_DISABLED,
    GMAIL_AUTHORIZATION_REJECTED,
    GMAIL_REFRESH_TOKEN_REQUIRED,
    GMAIL_PROVIDER_UNAVAILABLE,
    GMAIL_CONNECTION_CONFLICT,
    GMAIL_CONNECTION_UNAVAILABLE,
    CLIENT_SESSION_DEVICE_REJECTED,
    CLIENT_SESSION_ACTIVE_TENANT_REQUIRED,
    CLIENT_SESSION_TENANT_FORBIDDEN,
    CLIENT_SESSION_UNAUTHENTICATED,
    CLIENT_SESSION_AUTHORITY_REJECTED,
    CLIENT_SESSION_UNAVAILABLE,
    UNEXPECTED_RESPONSE,
}

sealed interface GmailConnectionResult {
    data class Success(
        val connection: GmailConnection,
    ) : GmailConnectionResult

    data class Failure(
        val error: GmailConnectionErrorCode,
    ) : GmailConnectionResult
}

interface GmailConnectionClient {
    suspend fun getGmailConnection(
        session: ClientSessionCredential,
    ): GmailConnectionResult

    suspend fun connectGmail(
        session: ClientSessionCredential,
        authorizationCode: String,
    ): GmailConnectionResult

    suspend fun disconnectGmail(
        session: ClientSessionCredential,
    ): GmailConnectionResult
}

data class GmailConnectionTransportResponse(
    val statusCode: Int,
    val body: String?,
)

interface GmailConnectionTransport {
    suspend fun get(
        path: String,
        bearerToken: String,
    ): GmailConnectionTransportResponse

    suspend fun post(
        path: String,
        bearerToken: String,
        body: String,
    ): GmailConnectionTransportResponse

    suspend fun delete(
        path: String,
        bearerToken: String,
    ): GmailConnectionTransportResponse
}

class AttentionRouterGmailConnectionClient(
    baseUrl: String,
    private val transport: GmailConnectionTransport =
        OkHttpGmailConnectionTransport(baseUrl),
) : GmailConnectionClient {
    override suspend fun getGmailConnection(
        session: ClientSessionCredential,
    ): GmailConnectionResult = execute(session) { token ->
        transport.get(PATH, token)
    }

    override suspend fun connectGmail(
        session: ClientSessionCredential,
        authorizationCode: String,
    ): GmailConnectionResult {
        if (
            authorizationCode.length !in 8..8192 ||
            authorizationCode.isBlank()
        ) {
            return GmailConnectionResult.Failure(
                GmailConnectionErrorCode.UNEXPECTED_RESPONSE,
            )
        }

        val body = buildJsonObject {
            put("authorization_code", authorizationCode)
        }.toString()

        return execute(session) { token ->
            transport.post(PATH, token, body)
        }
    }

    override suspend fun disconnectGmail(
        session: ClientSessionCredential,
    ): GmailConnectionResult = execute(session) { token ->
        transport.delete(PATH, token)
    }

    private suspend fun execute(
        session: ClientSessionCredential,
        request: suspend (String) -> GmailConnectionTransportResponse,
    ): GmailConnectionResult {
        return try {
            val response = session.withToken(request)
            if (response.statusCode == 200) {
                parseConnection(response.body)
            } else {
                GmailConnectionResult.Failure(error(response.body))
            }
        } catch (_: Exception) {
            GmailConnectionResult.Failure(
                GmailConnectionErrorCode.NETWORK_FAILURE,
            )
        }
    }

    private fun parseConnection(
        body: String?,
    ): GmailConnectionResult {
        val value = obj(body) ?: return unexpected()
        if (
            value.keys != setOf(
                "contract_version",
                "provider",
                "product",
                "status",
                "installation_id",
                "granted_scopes",
            ) ||
            value.str("contract_version") != "1" ||
            value.str("provider") != "GOOGLE" ||
            value.str("product") != "GMAIL"
        ) {
            return unexpected()
        }

        val status = try {
            GmailConnectionStatus.valueOf(
                value.str("status") ?: return unexpected(),
            )
        } catch (_: Exception) {
            return unexpected()
        }

        val installationId = when (val raw = value["installation_id"]) {
            JsonNull -> null
            is JsonPrimitive -> raw
                .takeIf { it.isString }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() && it.length <= 64 }
                ?: return unexpected()
            else -> return unexpected()
        }

        val scopesValue =
            value["granted_scopes"] as? JsonArray ?: return unexpected()
        if (scopesValue.size > 20) return unexpected()
        val scopes = scopesValue.map { element ->
            val primitive = element as? JsonPrimitive ?: return unexpected()
            if (!primitive.isString) return unexpected()
            primitive.contentOrNull
                ?.takeIf { it.isNotBlank() && it.length <= 256 }
                ?: return unexpected()
        }.toSet()
        if (scopes.size != scopesValue.size) return unexpected()

        when (status) {
            GmailConnectionStatus.CONNECTED -> {
                if (
                    installationId == null ||
                    scopes.size != 1 ||
                    scopes.single() !in GMAIL_SUPPORTED_SCOPE_PROFILES
                ) {
                    return unexpected()
                }
            }
            GmailConnectionStatus.DISCONNECTED -> {
                if (installationId != null || scopes.isNotEmpty()) {
                    return unexpected()
                }
            }
        }

        return GmailConnectionResult.Success(
            GmailConnection(status, scopes),
        )
    }

    private fun error(body: String?) = when (obj(body)?.str("code")) {
        "GMAIL_CONNECTION_DISABLED" ->
            GmailConnectionErrorCode.GMAIL_CONNECTION_DISABLED
        "GMAIL_AUTHORIZATION_REJECTED" ->
            GmailConnectionErrorCode.GMAIL_AUTHORIZATION_REJECTED
        "GMAIL_REFRESH_TOKEN_REQUIRED" ->
            GmailConnectionErrorCode.GMAIL_REFRESH_TOKEN_REQUIRED
        "GMAIL_PROVIDER_UNAVAILABLE" ->
            GmailConnectionErrorCode.GMAIL_PROVIDER_UNAVAILABLE
        "GMAIL_CONNECTION_CONFLICT" ->
            GmailConnectionErrorCode.GMAIL_CONNECTION_CONFLICT
        "GMAIL_CONNECTION_UNAVAILABLE" ->
            GmailConnectionErrorCode.GMAIL_CONNECTION_UNAVAILABLE
        "CLIENT_SESSION_DEVICE_REJECTED" ->
            GmailConnectionErrorCode.CLIENT_SESSION_DEVICE_REJECTED
        "CLIENT_SESSION_ACTIVE_TENANT_REQUIRED" ->
            GmailConnectionErrorCode.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED
        "CLIENT_SESSION_TENANT_FORBIDDEN" ->
            GmailConnectionErrorCode.CLIENT_SESSION_TENANT_FORBIDDEN
        "CLIENT_SESSION_UNAUTHENTICATED" ->
            GmailConnectionErrorCode.CLIENT_SESSION_UNAUTHENTICATED
        "CLIENT_SESSION_AUTHORITY_REJECTED" ->
            GmailConnectionErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED
        "CLIENT_SESSION_UNAVAILABLE" ->
            GmailConnectionErrorCode.CLIENT_SESSION_UNAVAILABLE
        else -> GmailConnectionErrorCode.UNEXPECTED_RESPONSE
    }

    private fun unexpected() = GmailConnectionResult.Failure(
        GmailConnectionErrorCode.UNEXPECTED_RESPONSE,
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

    private companion object {
        const val PATH = "/api/v1/integrations/gmail"
    }
}

class OkHttpGmailConnectionTransport(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) : GmailConnectionTransport {
    override suspend fun get(
        path: String,
        bearerToken: String,
    ): GmailConnectionTransportResponse =
        execute(
            request(path, bearerToken)
                .get()
                .build(),
        )

    override suspend fun post(
        path: String,
        bearerToken: String,
        body: String,
    ): GmailConnectionTransportResponse =
        execute(
            request(path, bearerToken)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )

    override suspend fun delete(
        path: String,
        bearerToken: String,
    ): GmailConnectionTransportResponse =
        execute(
            request(path, bearerToken)
                .delete()
                .build(),
        )

    private fun request(
        path: String,
        bearerToken: String,
    ): Request.Builder {
        require(baseUrl.startsWith("https://"))
        require(path.startsWith("/"))
        require(bearerToken.matches(SESSION_TOKEN))
        return Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
    }

    private suspend fun execute(
        request: Request,
    ) = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            GmailConnectionTransportResponse(
                response.code,
                response.body?.string(),
            )
        }
    }

    private companion object {
        val SESSION_TOKEN = Regex("^cst_[A-Za-z0-9_-]{43}$")
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
