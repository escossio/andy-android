package io.github.escossio.andy.core.deviceidentity

const val DEVICE_IDENTITY_SCHEMA_VERSION = 1
const val DEVICE_IDENTITY_KEY_ALIAS = "andy_device_identity_v1"

class DeviceIdentityEngine(
    private val metadataRepository: DeviceIdentityMetadataRepository,
    private val crypto: DeviceIdentityCrypto,
    private val challengeGenerator: () -> ByteArray,
    private val keyAlias: String = DEVICE_IDENTITY_KEY_ALIAS,
) : DeviceIdentityManager {
    override fun ensureIdentity(): DeviceIdentityResult = when (val loaded = metadataRepository.load()) {
        MetadataLoadResult.Absent -> provisionFresh()
        is MetadataLoadResult.Invalid -> DeviceIdentityResult.Unavailable(loaded.reason)
        is MetadataLoadResult.Present -> when (loaded.metadata.state) {
            StoredIdentityState.PROVISIONING -> resumeProvisioning(loaded.metadata)
            StoredIdentityState.READY -> validateReady(loaded.metadata)
        }
    }

    private fun provisionFresh(): DeviceIdentityResult = try {
        if (crypto.hasKey(keyAlias)) {
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.INCONSISTENT_FRESH_INSTALL)
        } else {
            metadataRepository.writeProvisioning(keyAlias)
            crypto.generateKey(keyAlias)
            completeProvisioning()
        }
    } catch (_: Exception) {
        DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.PROVISIONING_FAILED)
    }

    private fun resumeProvisioning(metadata: StoredIdentityMetadata): DeviceIdentityResult {
        if (metadata.keyAlias != keyAlias) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
        }
        return try {
            if (!crypto.hasKey(keyAlias)) crypto.generateKey(keyAlias)
            completeProvisioning()
        } catch (_: Exception) {
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.PROVISIONING_FAILED)
        }
    }

    private fun completeProvisioning(): DeviceIdentityResult {
        val fingerprint = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(
            crypto.publicKeySubjectPublicKeyInfo(keyAlias),
        )
        if (!selfTest()) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.SELF_TEST_FAILED)
        }
        metadataRepository.writeReady(keyAlias, fingerprint)
        return DeviceIdentityResult.Ready(fingerprint)
    }

    private fun validateReady(metadata: StoredIdentityMetadata): DeviceIdentityResult {
        if (metadata.schemaVersion != DEVICE_IDENTITY_SCHEMA_VERSION ||
            metadata.keyAlias != keyAlias ||
            metadata.publicKeyFingerprint == null
        ) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
        }
        return try {
            if (!crypto.hasKey(keyAlias)) {
                return DeviceIdentityResult.Unavailable(
                    DeviceIdentityUnavailableReason.ESTABLISHED_KEY_MISSING,
                )
            }
            val actual = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(
                crypto.publicKeySubjectPublicKeyInfo(keyAlias),
            )
            if (actual != metadata.publicKeyFingerprint) {
                return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.FINGERPRINT_MISMATCH)
            }
            if (!selfTest()) {
                return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.SELF_TEST_FAILED)
            }
            DeviceIdentityResult.Ready(actual)
        } catch (_: Exception) {
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.ESTABLISHED_KEY_INACCESSIBLE)
        }
    }

    private fun selfTest(): Boolean {
        val challenge = challengeGenerator()
        val signature = crypto.signForSelfTest(keyAlias, challenge)
        return crypto.verifySelfTest(keyAlias, challenge, signature)
    }
}
