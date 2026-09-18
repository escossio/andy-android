package io.github.escossio.andy.sdk.clientapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

enum class DeviceBootstrapRole { CLIENT, CAPABILITY_NODE }
enum class ClientTenantRole { OWNER, ADMIN, MEMBER }

data class DeviceBootstrapChallenge(
    val challengeId: String,
    val challengeB64Url: String,
    val expiresAt: Instant,
)

data class ClientTenantMembership(
    val membershipId: String,
    val tenantId: String,
    val role: ClientTenantRole,
)

data class ClientDevice(
    val deviceId: String,
    val publicKeyFingerprint: String,
    val canonicalName: String,
    val roles: Set<DeviceBootstrapRole>,
)

data class DeviceBootstrapEstablished(
    val humanIdentityId: String,
    val memberships: List<ClientTenantMembership>,
    val initialTenantId: String?,
    val device: ClientDevice,
)

enum class DeviceBootstrapErrorCode {
    NETWORK_FAILURE,
    DEVICE_BOOTSTRAP_GRANT_REJECTED,
    DEVICE_BOOTSTRAP_CHALLENGE_NOT_FOUND,
    DEVICE_BOOTSTRAP_CHALLENGE_EXPIRED,
    DEVICE_BOOTSTRAP_CHALLENGE_CONSUMED,
    DEVICE_BOOTSTRAP_DEVICE_KEY_INVALID,
    DEVICE_BOOTSTRAP_SIGNATURE_INVALID,
    DEVICE_BOOTSTRAP_MEMBERSHIP_CONFLICT,
    DEVICE_BOOTSTRAP_DEVICE_CONFLICT,
    DEVICE_BOOTSTRAP_UNAVAILABLE,
    UNEXPECTED_RESPONSE,
}

sealed interface DeviceBootstrapChallengeResult {
    data class Success(val challenge: DeviceBootstrapChallenge) : DeviceBootstrapChallengeResult
    data class Failure(val error: DeviceBootstrapErrorCode) : DeviceBootstrapChallengeResult
}

sealed interface DeviceBootstrapCompleteResult {
    data class Success(val established: DeviceBootstrapEstablished) : DeviceBootstrapCompleteResult
    data class Failure(val error: DeviceBootstrapErrorCode) : DeviceBootstrapCompleteResult
}

interface DeviceBootstrapClient {
    suspend fun start(
        grant: HumanAuthContinuationGrant,
        publicKeySpkiB64Url: String,
        canonicalDeviceName: String,
        roles: Set<DeviceBootstrapRole>,
    ): DeviceBootstrapChallengeResult

    suspend fun complete(
        challengeId: String,
        signatureB64Url: String,
    ): DeviceBootstrapCompleteResult
}

class AttentionRouterDeviceBootstrapClient(
    baseUrl: String,
    private val transport: HumanAuthTransport = OkHttpHumanAuthTransport(baseUrl),
) : DeviceBootstrapClient {
    override suspend fun start(
        grant: HumanAuthContinuationGrant,
        publicKeySpkiB64Url: String,
        canonicalDeviceName: String,
        roles: Set<DeviceBootstrapRole>,
    ): DeviceBootstrapChallengeResult {
        if (
            grant.purpose != HumanAuthContinuationPurpose.DEVICE_BOOTSTRAP ||
            !publicKeySpkiB64Url.matches(B64URL) ||
            publicKeySpkiB64Url.length !in 80..2048 ||
            canonicalDeviceName.isBlank() ||
            canonicalDeviceName.length > 160 ||
            roles.isEmpty() ||
            roles.size > 2
        ) {
            return DeviceBootstrapChallengeResult.Failure(
                DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE,
            )
        }
        val body = grant.withToken { token ->
            buildJsonObject {
                put("continuation_token", token)
                put("public_key_spki_b64url", publicKeySpkiB64Url)
                put("canonical_device_name", canonicalDeviceName)
                put("platform", "ANDROID")
                putJsonArray("roles") {
                    roles.sortedBy { it.name }.forEach { add(it.name) }
                }
            }.toString()
        }
        return try {
            val response = transport.post(PATH, body)
            if (response.statusCode == 201) {
                parseChallenge(response.body)
            } else {
                DeviceBootstrapChallengeResult.Failure(error(response.body))
            }
        } catch (_: Exception) {
            DeviceBootstrapChallengeResult.Failure(DeviceBootstrapErrorCode.NETWORK_FAILURE)
        }
    }

    override suspend fun complete(
        challengeId: String,
        signatureB64Url: String,
    ): DeviceBootstrapCompleteResult {
        if (
            !challengeId.matches(CHALLENGE_ID) ||
            !signatureB64Url.matches(B64URL) ||
            signatureB64Url.length !in 64..512
        ) {
            return DeviceBootstrapCompleteResult.Failure(
                DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE,
            )
        }
        return try {
            val body = buildJsonObject {
                put("device_signature_b64url", signatureB64Url)
            }.toString()
            val response = transport.post("$PATH/$challengeId/complete", body)
            if (response.statusCode == 200) {
                parseEstablished(response.body)
            } else {
                DeviceBootstrapCompleteResult.Failure(error(response.body))
            }
        } catch (_: Exception) {
            DeviceBootstrapCompleteResult.Failure(DeviceBootstrapErrorCode.NETWORK_FAILURE)
        }
    }

    private fun parseChallenge(body: String?): DeviceBootstrapChallengeResult {
        val value = obj(body)
            ?: return DeviceBootstrapChallengeResult.Failure(
                DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE,
            )
        if (value.keys != setOf("bootstrap_challenge_id", "challenge_b64url", "expires_at")) {
            return DeviceBootstrapChallengeResult.Failure(
                DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE,
            )
        }
        val challengeId = value.str("bootstrap_challenge_id")
            ?.takeIf { it.matches(CHALLENGE_ID) }
            ?: return unexpectedChallenge()
        val challenge = value.str("challenge_b64url")
            ?.takeIf { it.length in 32..256 && it.matches(B64URL) }
            ?: return unexpectedChallenge()
        val expiresAt = try {
            Instant.parse(value.str("expires_at") ?: return unexpectedChallenge())
        } catch (_: Exception) {
            return unexpectedChallenge()
        }
        return DeviceBootstrapChallengeResult.Success(
            DeviceBootstrapChallenge(challengeId, challenge, expiresAt),
        )
    }

    private fun parseEstablished(body: String?): DeviceBootstrapCompleteResult {
        val value = obj(body) ?: return unexpectedComplete()
        if (
            value.keys != setOf(
                "status",
                "human_identity_id",
                "memberships",
                "initial_tenant_id",
                "device",
            ) ||
            value.str("status") != "DEVICE_BOOTSTRAP_ESTABLISHED"
        ) {
            return unexpectedComplete()
        }
        val humanIdentityId = value.str("human_identity_id")
            ?.takeIf { it.matches(HUMAN_ID) }
            ?: return unexpectedComplete()
        val membershipsValue = value["memberships"] as? JsonArray ?: return unexpectedComplete()
        if (membershipsValue.isEmpty() || membershipsValue.size > 100) return unexpectedComplete()
        val memberships = membershipsValue.map { element ->
            val membership = element as? JsonObject ?: return unexpectedComplete()
            if (membership.keys != setOf("membership_id", "tenant_id", "role", "status")) {
                return unexpectedComplete()
            }
            val membershipId = membership.str("membership_id")
                ?.takeIf { it.length in 1..64 }
                ?: return unexpectedComplete()
            val tenantId = membership.str("tenant_id")
                ?.takeIf { it.length in 1..64 }
                ?: return unexpectedComplete()
            val role = try {
                ClientTenantRole.valueOf(
                    membership.str("role") ?: return unexpectedComplete(),
                )
            } catch (_: Exception) {
                return unexpectedComplete()
            }
            if (membership.str("status") != "ACTIVE") return unexpectedComplete()
            ClientTenantMembership(membershipId, tenantId, role)
        }
        val initialTenantId = when (val initial = value["initial_tenant_id"]) {
            JsonNull -> null
            is JsonPrimitive -> initial
                .takeIf { it.isString }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() && it.length <= 64 }
                ?: return unexpectedComplete()
            else -> return unexpectedComplete()
        }
        val deviceValue = value["device"] as? JsonObject ?: return unexpectedComplete()
        if (
            deviceValue.keys != setOf(
                "device_id",
                "public_key_fingerprint",
                "canonical_name",
                "platform",
                "roles",
                "status",
            ) ||
            deviceValue.str("platform") != "ANDROID" ||
            deviceValue.str("status") != "ACTIVE"
        ) {
            return unexpectedComplete()
        }
        val deviceId = deviceValue.str("device_id")
            ?.takeIf { it.matches(DEVICE_ID) }
            ?: return unexpectedComplete()
        val fingerprint = deviceValue.str("public_key_fingerprint")
            ?.takeIf { it.matches(FINGERPRINT) }
            ?: return unexpectedComplete()
        val canonicalName = deviceValue.str("canonical_name")
            ?.takeIf { it.isNotBlank() && it.length <= 160 }
            ?: return unexpectedComplete()
        val roleValues = deviceValue["roles"] as? JsonArray ?: return unexpectedComplete()
        if (roleValues.isEmpty() || roleValues.size > 2) return unexpectedComplete()
        val roles = roleValues.map { element ->
            val primitive = element as? JsonPrimitive ?: return unexpectedComplete()
            if (!primitive.isString) return unexpectedComplete()
            val raw = primitive.contentOrNull ?: return unexpectedComplete()
            try {
                DeviceBootstrapRole.valueOf(raw)
            } catch (_: Exception) {
                return unexpectedComplete()
            }
        }.toSet()
        if (roles.size != roleValues.size) return unexpectedComplete()

        return DeviceBootstrapCompleteResult.Success(
            DeviceBootstrapEstablished(
                humanIdentityId = humanIdentityId,
                memberships = memberships,
                initialTenantId = initialTenantId,
                device = ClientDevice(
                    deviceId = deviceId,
                    publicKeyFingerprint = fingerprint,
                    canonicalName = canonicalName,
                    roles = roles,
                ),
            ),
        )
    }

    private fun unexpectedChallenge() = DeviceBootstrapChallengeResult.Failure(
        DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE,
    )

    private fun unexpectedComplete() = DeviceBootstrapCompleteResult.Failure(
        DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE,
    )

    private fun error(body: String?) = when (obj(body)?.str("code")) {
        "DEVICE_BOOTSTRAP_GRANT_REJECTED" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_GRANT_REJECTED
        "DEVICE_BOOTSTRAP_CHALLENGE_NOT_FOUND" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_NOT_FOUND
        "DEVICE_BOOTSTRAP_CHALLENGE_EXPIRED" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_EXPIRED
        "DEVICE_BOOTSTRAP_CHALLENGE_CONSUMED" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_CONSUMED
        "DEVICE_BOOTSTRAP_DEVICE_KEY_INVALID" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_DEVICE_KEY_INVALID
        "DEVICE_BOOTSTRAP_SIGNATURE_INVALID" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_SIGNATURE_INVALID
        "DEVICE_BOOTSTRAP_MEMBERSHIP_CONFLICT" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_MEMBERSHIP_CONFLICT
        "DEVICE_BOOTSTRAP_DEVICE_CONFLICT" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_DEVICE_CONFLICT
        "DEVICE_BOOTSTRAP_UNAVAILABLE" ->
            DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_UNAVAILABLE
        else -> DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE
    }

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
        const val PATH = "/api/v1/bootstrap/device/challenges"
        val B64URL = Regex("^[A-Za-z0-9_-]+$")
        val CHALLENGE_ID = Regex("^dbc_[A-Za-z0-9_-]{20,}$")
        val HUMAN_ID = Regex("^hid_[A-Za-z0-9_-]{20,}$")
        val DEVICE_ID = Regex("^cdev_[A-Za-z0-9_-]{20,}$")
        val FINGERPRINT = Regex("^sha256:[0-9a-f]{64}$")
    }
}
