package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

data class ClientLocationObservation(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val capturedAt: Instant,
    val precision: String?,
)

data class ClientLocationSnapshot(
    val locationSnapshotId: String,
    val humanIdentityId: String,
    val deviceId: String,
    val tenantId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val precision: String?,
    val capturedAt: Instant,
    val receivedAt: Instant,
)

enum class ClientLocationErrorCode {
    NETWORK_FAILURE,
    CLIENT_LOCATION_DISABLED,
    CLIENT_LOCATION_INVALID,
    CLIENT_LOCATION_STALE,
    CLIENT_LOCATION_FUTURE,
    CLIENT_LOCATION_UNAUTHENTICATED,
    CLIENT_LOCATION_AUTHORITY_REJECTED,
    CLIENT_LOCATION_NOT_FOUND,
    CLIENT_LOCATION_UNAVAILABLE,
    UNEXPECTED_RESPONSE,
}

sealed interface ClientLocationResult {
    data class Success(val snapshot: ClientLocationSnapshot) : ClientLocationResult
    data class Failure(val error: ClientLocationErrorCode) : ClientLocationResult
}

interface ClientLocationClient {
    suspend fun putCurrent(
        session: ClientSessionCredential,
        observation: ClientLocationObservation,
    ): ClientLocationResult

    suspend fun getCurrent(session: ClientSessionCredential): ClientLocationResult
}

class AttentionRouterClientLocationClient(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) : ClientLocationClient {
    private val root = baseUrl.trimEnd('/')

    override suspend fun putCurrent(
        session: ClientSessionCredential,
        observation: ClientLocationObservation,
    ): ClientLocationResult {
        if (
            !observation.latitude.isFinite() || observation.latitude !in -90.0..90.0 ||
            !observation.longitude.isFinite() || observation.longitude !in -180.0..180.0 ||
            !observation.accuracyM.isFinite() || observation.accuracyM <= 0.0 ||
            observation.accuracyM > 10_000.0 ||
            observation.precision !in setOf(null, "PRECISE", "APPROXIMATE")
        ) {
            return ClientLocationResult.Failure(ClientLocationErrorCode.CLIENT_LOCATION_INVALID)
        }
        val body = buildJsonObject {
            put("latitude", observation.latitude)
            put("longitude", observation.longitude)
            put("accuracy_m", observation.accuracyM)
            put("captured_at", observation.capturedAt.toString())
            observation.precision?.let { put("precision", it) }
        }.toString()

        return request(session, "PUT", body)
    }

    override suspend fun getCurrent(
        session: ClientSessionCredential,
    ): ClientLocationResult = request(session, "GET", null)

    private suspend fun request(
        session: ClientSessionCredential,
        method: String,
        body: String?,
    ): ClientLocationResult = try {
        session.withToken { token ->
            withContext(Dispatchers.IO) {
                val builder = Request.Builder()
                    .url("$root/api/v1/client/location/current")
                    .header("Authorization", "Bearer $token")
                if (method == "PUT") {
                    builder.put(
                        requireNotNull(body).toRequestBody(
                            "application/json; charset=utf-8".toMediaType(),
                        ),
                    )
                } else {
                    builder.get()
                }
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string()
                    if (response.isSuccessful) parseSnapshot(text)
                    else ClientLocationResult.Failure(parseError(text))
                }
            }
        }
    } catch (_: Exception) {
        ClientLocationResult.Failure(ClientLocationErrorCode.NETWORK_FAILURE)
    }

    private fun parseSnapshot(body: String?): ClientLocationResult {
        val value = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return ClientLocationResult.Failure(ClientLocationErrorCode.UNEXPECTED_RESPONSE)
        if (value.string("contract_version") != "1") {
            return ClientLocationResult.Failure(ClientLocationErrorCode.UNEXPECTED_RESPONSE)
        }
        val snapshot = ClientLocationSnapshot(
            locationSnapshotId = value.string("location_snapshot_id") ?: return unexpected(),
            humanIdentityId = value.string("human_identity_id") ?: return unexpected(),
            deviceId = value.string("device_id") ?: return unexpected(),
            tenantId = value.string("tenant_id") ?: return unexpected(),
            latitude = value.double("latitude") ?: return unexpected(),
            longitude = value.double("longitude") ?: return unexpected(),
            accuracyM = value.double("accuracy_m") ?: return unexpected(),
            precision = value.string("precision"),
            capturedAt = value.instant("captured_at") ?: return unexpected(),
            receivedAt = value.instant("received_at") ?: return unexpected(),
        )
        return ClientLocationResult.Success(snapshot)
    }

    private fun parseError(body: String?): ClientLocationErrorCode {
        val code = body?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject.string("code") }.getOrNull()
        }
        return runCatching { ClientLocationErrorCode.valueOf(code ?: "") }
            .getOrDefault(ClientLocationErrorCode.UNEXPECTED_RESPONSE)
    }

    private fun unexpected() =
        ClientLocationResult.Failure(ClientLocationErrorCode.UNEXPECTED_RESPONSE)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.instant(key: String): Instant? =
        string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
