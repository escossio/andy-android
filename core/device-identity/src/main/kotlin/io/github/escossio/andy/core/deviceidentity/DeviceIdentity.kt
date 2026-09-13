package io.github.escossio.andy.core.deviceidentity

import java.security.MessageDigest

@JvmInline
value class DeviceKeyFingerprint private constructor(val value: String) {
    companion object {
        private val canonical = Regex("^sha256:[0-9a-f]{64}$")

        fun fromSubjectPublicKeyInfo(encoded: ByteArray): DeviceKeyFingerprint {
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
            val hex = digest.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            return DeviceKeyFingerprint("sha256:$hex")
        }

        fun parse(value: String): DeviceKeyFingerprint? =
            value.takeIf(canonical::matches)?.let(::DeviceKeyFingerprint)
    }
}

sealed interface DeviceIdentityResult {
    data class Ready(val fingerprint: DeviceKeyFingerprint) : DeviceIdentityResult
    data class Unavailable(val reason: DeviceIdentityUnavailableReason) : DeviceIdentityResult
}

enum class DeviceIdentityUnavailableReason {
    METADATA_MALFORMED,
    METADATA_UNSUPPORTED,
    ESTABLISHED_KEY_MISSING,
    ESTABLISHED_KEY_INACCESSIBLE,
    FINGERPRINT_MISMATCH,
    SELF_TEST_FAILED,
    INCONSISTENT_FRESH_INSTALL,
    PROVISIONING_FAILED,
}

interface DeviceIdentityManager {
    fun ensureIdentity(): DeviceIdentityResult
}

enum class StoredIdentityState { PROVISIONING, READY }

data class StoredIdentityMetadata(
    val schemaVersion: Int,
    val state: StoredIdentityState,
    val keyAlias: String,
    val publicKeyFingerprint: DeviceKeyFingerprint?,
)

sealed interface MetadataLoadResult {
    data object Absent : MetadataLoadResult
    data class Present(val metadata: StoredIdentityMetadata) : MetadataLoadResult
    data class Invalid(val reason: DeviceIdentityUnavailableReason) : MetadataLoadResult
}

interface DeviceIdentityMetadataRepository {
    fun load(): MetadataLoadResult
    fun writeProvisioning(keyAlias: String)
    fun writeReady(keyAlias: String, fingerprint: DeviceKeyFingerprint)
}

interface DeviceIdentityCrypto {
    fun hasKey(keyAlias: String): Boolean
    fun generateKey(keyAlias: String)
    fun publicKeySubjectPublicKeyInfo(keyAlias: String): ByteArray
    fun signForSelfTest(keyAlias: String, challenge: ByteArray): ByteArray
    fun verifySelfTest(keyAlias: String, challenge: ByteArray, signature: ByteArray): Boolean
}
