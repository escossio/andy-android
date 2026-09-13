package io.github.escossio.andy.core.deviceidentity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityEngineTest {
    private val publicKey = byteArrayOf(1, 3, 3, 7)
    private val fingerprint = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(publicKey)

    @Test
    fun absentStateProvisionsOnceAndBecomesReady() {
        val metadata = FakeMetadataRepository(MetadataLoadResult.Absent)
        val crypto = FakeCrypto(false)
        crypto.beforeGenerate = { assertEquals(StoredIdentityState.PROVISIONING, metadata.current().state) }
        val manager = engine(metadata, crypto)
        assertEquals(DeviceIdentityResult.Ready(fingerprint), manager.ensureIdentity())
        assertEquals(1, crypto.generateCount)
        assertEquals(StoredIdentityState.READY, metadata.current().state)
        assertEquals(fingerprint, metadata.current().publicKeyFingerprint)
        assertEquals(listOf("PROVISIONING", "READY"), metadata.writes)
        assertEquals(DeviceIdentityResult.Ready(fingerprint), manager.ensureIdentity())
        assertEquals(1, crypto.generateCount)
        assertEquals(2, crypto.signCount)
        assertEquals(2, crypto.verifyCount)
        assertTrue(!crypto.challenges[0].contentEquals(crypto.challenges[1]))
    }

    @Test
    fun readyStateReusesExistingIdentityWithoutGeneration() {
        val metadata = readyMetadata()
        val crypto = FakeCrypto(true)
        val manager = engine(metadata, crypto)
        repeat(2) { assertEquals(DeviceIdentityResult.Ready(fingerprint), manager.ensureIdentity()) }
        assertEquals(0, crypto.generateCount)
        assertTrue(metadata.writes.isEmpty())
        assertEquals(2, crypto.publicKeyReads)
        assertEquals(2, crypto.signCount)
        assertEquals(2, crypto.verifyCount)
        assertTrue(!crypto.challenges[0].contentEquals(crypto.challenges[1]))
    }

    @Test
    fun provisioningWithExistingKeyCompletesWithoutGeneratingAgain() {
        val metadata = provisioningMetadata()
        val crypto = FakeCrypto(true)
        assertEquals(DeviceIdentityResult.Ready(fingerprint), engine(metadata, crypto).ensureIdentity())
        assertEquals(0, crypto.generateCount)
        assertEquals(listOf("READY"), metadata.writes)
    }

    @Test
    fun provisioningWithoutKeyGeneratesAndCompletes() {
        val metadata = provisioningMetadata()
        val crypto = FakeCrypto(false)
        assertEquals(DeviceIdentityResult.Ready(fingerprint), engine(metadata, crypto).ensureIdentity())
        assertEquals(1, crypto.generateCount)
        assertEquals(StoredIdentityState.READY, metadata.current().state)
    }

    @Test
    fun readyWithMissingKeyIsUnavailableAndNeverGenerates() =
        assertReadyFailure(FakeCrypto(false), DeviceIdentityUnavailableReason.ESTABLISHED_KEY_MISSING)

    @Test
    fun readyWithFingerprintMismatchIsUnavailableAndNeverGenerates() =
        assertReadyFailure(FakeCrypto(true).apply { keyBytes = byteArrayOf(9) }, DeviceIdentityUnavailableReason.FINGERPRINT_MISMATCH)

    @Test
    fun readyWithFailedSelfTestIsUnavailableAndNeverGenerates() =
        assertReadyFailure(FakeCrypto(true).apply { verifyResult = false }, DeviceIdentityUnavailableReason.SELF_TEST_FAILED)

    @Test
    fun establishedKeyExceptionIsUnavailableAndNeverGenerates() {
        for (operation in listOf("hasKey", "publicKey", "sign", "verify")) {
            assertReadyFailure(
                FakeCrypto(true).apply { failOperation = operation },
                DeviceIdentityUnavailableReason.ESTABLISHED_KEY_INACCESSIBLE,
            )
        }
    }

    @Test
    fun invalidMetadataReturnsUnavailableWithoutKeyMutation() {
        for (reason in listOf(DeviceIdentityUnavailableReason.METADATA_MALFORMED, DeviceIdentityUnavailableReason.METADATA_UNSUPPORTED)) {
            val metadata = FakeMetadataRepository(MetadataLoadResult.Invalid(reason))
            val crypto = FakeCrypto(true)
            assertEquals(DeviceIdentityResult.Unavailable(reason), engine(metadata, crypto).ensureIdentity())
            assertEquals(0, crypto.generateCount)
            assertEquals(0, crypto.signCount)
            assertTrue(metadata.writes.isEmpty())
        }
    }

    @Test
    fun absentMetadataWithUnexpectedKeyIsUnavailableAndNeverGenerates() {
        val metadata = FakeMetadataRepository(MetadataLoadResult.Absent)
        val crypto = FakeCrypto(true)
        assertEquals(
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.INCONSISTENT_FRESH_INSTALL),
            engine(metadata, crypto).ensureIdentity(),
        )
        assertEquals(0, crypto.generateCount)
        assertTrue(metadata.writes.isEmpty())
    }

    @Test
    fun readyWithInvalidMetadataNeverMutatesKeyOrMetadata() {
        val valid = readyMetadata().current()
        for (invalid in listOf(valid.copy(schemaVersion = 2), valid.copy(keyAlias = "wrong_synthetic_alias"), valid.copy(publicKeyFingerprint = null))) {
            val metadata = FakeMetadataRepository(MetadataLoadResult.Present(invalid))
            val crypto = FakeCrypto(true)
            assertEquals(
                DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.METADATA_MALFORMED),
                engine(metadata, crypto).ensureIdentity(),
            )
            assertEquals(0, crypto.generateCount)
            assertEquals(0, crypto.signCount)
            assertTrue(metadata.writes.isEmpty())
        }
    }

    @Test
    fun provisioningWithWrongAliasFailsClosed() {
        val metadata = FakeMetadataRepository(MetadataLoadResult.Present(
            StoredIdentityMetadata(1, StoredIdentityState.PROVISIONING, "wrong_synthetic_alias", null),
        ))
        val crypto = FakeCrypto(false)
        assertEquals(
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.METADATA_MALFORMED),
            engine(metadata, crypto).ensureIdentity(),
        )
        assertEquals(0, crypto.generateCount)
        assertTrue(metadata.writes.isEmpty())
    }

    @Test
    fun failedProvisioningWriteDoesNotGenerateKey() {
        val metadata = FakeMetadataRepository(MetadataLoadResult.Absent).apply { failWrite = true }
        val crypto = FakeCrypto(false)
        assertEquals(
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.PROVISIONING_FAILED),
            engine(metadata, crypto).ensureIdentity(),
        )
        assertEquals(0, crypto.generateCount)
    }

    private fun assertReadyFailure(crypto: FakeCrypto, reason: DeviceIdentityUnavailableReason) {
        val metadata = readyMetadata()
        repeat(2) { assertEquals(DeviceIdentityResult.Unavailable(reason), engine(metadata, crypto).ensureIdentity()) }
        assertEquals(0, crypto.generateCount)
        assertTrue(metadata.writes.isEmpty())
        assertEquals(StoredIdentityState.READY, metadata.current().state)
    }

    private fun readyMetadata() = FakeMetadataRepository(MetadataLoadResult.Present(
        StoredIdentityMetadata(1, StoredIdentityState.READY, TEST_ALIAS, fingerprint),
    ))

    private fun provisioningMetadata() = FakeMetadataRepository(MetadataLoadResult.Present(
        StoredIdentityMetadata(1, StoredIdentityState.PROVISIONING, TEST_ALIAS, null),
    ))

    private fun engine(metadata: FakeMetadataRepository, crypto: FakeCrypto): DeviceIdentityEngine {
        var sequence = 0
        return DeviceIdentityEngine(metadata, crypto, { ByteArray(32) { sequence.toByte() }.also { sequence++ } }, TEST_ALIAS)
    }

    private class FakeMetadataRepository(private var loaded: MetadataLoadResult) : DeviceIdentityMetadataRepository {
        val writes = mutableListOf<String>()
        var failWrite = false
        override fun load() = loaded
        fun current() = (loaded as MetadataLoadResult.Present).metadata
        override fun writeProvisioning(keyAlias: String) {
            check(!failWrite)
            writes += "PROVISIONING"
            loaded = MetadataLoadResult.Present(StoredIdentityMetadata(1, StoredIdentityState.PROVISIONING, keyAlias, null))
        }
        override fun writeReady(keyAlias: String, fingerprint: DeviceKeyFingerprint) {
            check(!failWrite)
            writes += "READY"
            loaded = MetadataLoadResult.Present(StoredIdentityMetadata(1, StoredIdentityState.READY, keyAlias, fingerprint))
        }
    }

    private inner class FakeCrypto(private var keyExists: Boolean) : DeviceIdentityCrypto {
        var keyBytes = publicKey
        var generateCount = 0
        var publicKeyReads = 0
        var signCount = 0
        var verifyCount = 0
        var verifyResult = true
        var failOperation: String? = null
        var beforeGenerate: () -> Unit = {}
        val challenges = mutableListOf<ByteArray>()
        private fun checkCall(alias: String, operation: String) {
            assertEquals(TEST_ALIAS, alias)
            check(failOperation != operation)
        }
        override fun hasKey(keyAlias: String): Boolean {
            checkCall(keyAlias, "hasKey")
            return keyExists
        }
        override fun generateKey(keyAlias: String) {
            checkCall(keyAlias, "generate")
            beforeGenerate()
            check(!keyExists)
            generateCount++
            keyExists = true
        }
        override fun publicKeySubjectPublicKeyInfo(keyAlias: String): ByteArray {
            checkCall(keyAlias, "publicKey")
            publicKeyReads++
            return keyBytes
        }
        override fun signForSelfTest(keyAlias: String, challenge: ByteArray): ByteArray {
            checkCall(keyAlias, "sign")
            signCount++
            challenges += challenge.copyOf()
            return byteArrayOf(42)
        }
        override fun verifySelfTest(keyAlias: String, challenge: ByteArray, signature: ByteArray): Boolean {
            checkCall(keyAlias, "verify")
            verifyCount++
            assertTrue(challenges.last().contentEquals(challenge))
            assertTrue(signature.contentEquals(byteArrayOf(42)))
            return verifyResult
        }
    }

    private companion object {
        const val TEST_ALIAS = "synthetic_engine_alias"
    }
}
