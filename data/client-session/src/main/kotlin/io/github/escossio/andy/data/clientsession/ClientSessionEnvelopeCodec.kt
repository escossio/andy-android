package io.github.escossio.andy.data.clientsession

import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Base64

internal data class EncryptedClientSessionEnvelope(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal object ClientSessionEnvelopeCodec {
    private const val SCHEMA_VERSION = 1
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encodeEnvelope(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == 12)
        require(ciphertext.size in 16..8192)
        return buildJsonObject {
            put("schema_version", SCHEMA_VERSION)
            put("iv_b64url", encoder.encodeToString(iv))
            put("ciphertext_b64url", encoder.encodeToString(ciphertext))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeEnvelope(bytes: ByteArray): EncryptedClientSessionEnvelope? = try {
        if (bytes.isEmpty() || bytes.size > 16384) return null
        val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        if (value.keys != setOf("schema_version", "iv_b64url", "ciphertext_b64url")) return null
        if ((value["schema_version"] as? JsonPrimitive)?.intOrNull != SCHEMA_VERSION) return null

        val iv = decode(value.string("iv_b64url")) ?: return null
        val ciphertext = decode(value.string("ciphertext_b64url")) ?: return null
        if (iv.size != 12 || ciphertext.size !in 16..8192) return null
        EncryptedClientSessionEnvelope(iv, ciphertext)
    } catch (_: Exception) {
        null
    }

    suspend fun encodeCredential(session: ClientSessionCredential): ByteArray =
        session.withToken { token ->
            buildJsonObject {
                put("schema_version", SCHEMA_VERSION)
                put("session_token", token)
                put("session_id", session.sessionId)
                put("expires_at", session.expiresAt.toString())
                put("human_identity_id", session.humanIdentityId)
                put("device_id", session.deviceId)
                put("tenant_id", session.tenantId)
            }.toString().toByteArray(Charsets.UTF_8)
        }

    fun decodeCredential(bytes: ByteArray): ClientSessionCredential? = try {
        if (bytes.isEmpty() || bytes.size > 4096) return null
        val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        if (
            value.keys != setOf(
                "schema_version",
                "session_token",
                "session_id",
                "expires_at",
                "human_identity_id",
                "device_id",
                "tenant_id",
            )
        ) return null
        if ((value["schema_version"] as? JsonPrimitive)?.intOrNull != SCHEMA_VERSION) return null

        ClientSessionCredential(
            token = value.string("session_token") ?: return null,
            sessionId = value.string("session_id") ?: return null,
            expiresAt = Instant.parse(value.string("expires_at") ?: return null),
            humanIdentityId = value.string("human_identity_id") ?: return null,
            deviceId = value.string("device_id") ?: return null,
            tenantId = value.string("tenant_id") ?: return null,
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun decode(value: String?): ByteArray? = try {
        value?.let(decoder::decode)
    } catch (_: IllegalArgumentException) {
        null
    }
}
