package io.github.escossio.andy.data.deviceidentity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityUnavailableReason
import io.github.escossio.andy.core.deviceidentity.DeviceKeyFingerprint
import io.github.escossio.andy.core.deviceidentity.MetadataLoadResult
import io.github.escossio.andy.core.deviceidentity.StoredIdentityState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidDeviceIdentityInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testFileName = "device_identity_test_${System.nanoTime()}.json"
    private val metadataFile get() = File(context.noBackupFilesDir, testFileName)

    @After
    fun cleanupMetadata() {
        metadataFile.delete()
        File(metadataFile.path + ".bak").delete()
        File(metadataFile.path + ".new").delete()
    }

    @Test
    fun metadataLivesUnderNoBackupFilesDirAndRoundTripsProvisioning() {
        val repository = NoBackupDeviceIdentityMetadataRepository(context, testFileName)
        repository.writeProvisioning("synthetic_alias")
        assertEquals(context.noBackupFilesDir.canonicalFile, metadataFile.canonicalFile.parentFile)
        val loaded = repository.load() as MetadataLoadResult.Present
        assertEquals(StoredIdentityState.PROVISIONING, loaded.metadata.state)
        assertEquals("synthetic_alias", loaded.metadata.keyAlias)
    }

    @Test
    fun malformedMetadataFailsClosed() {
        metadataFile.writeText("not-json")
        val loaded = NoBackupDeviceIdentityMetadataRepository(context, testFileName).load()
        assertEquals(MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_MALFORMED), loaded)
    }

    @Test
    fun unsupportedSchemaFailsClosed() {
        metadataFile.writeText("""{"schema_version":2,"state":"READY","key_alias":"synthetic_alias"}""")
        assertEquals(
            MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_UNSUPPORTED),
            NoBackupDeviceIdentityMetadataRepository(context, testFileName).load(),
        )
    }

    @Test
    fun readyMetadataRoundTripsExactly() {
        val fingerprint = requireNotNull(DeviceKeyFingerprint.parse("sha256:" + "a".repeat(64)))
        val repository = NoBackupDeviceIdentityMetadataRepository(context, testFileName)
        repository.writeReady("synthetic_alias", fingerprint)
        val loaded = repository.load() as MetadataLoadResult.Present
        assertEquals(1, loaded.metadata.schemaVersion)
        assertEquals(StoredIdentityState.READY, loaded.metadata.state)
        assertEquals("synthetic_alias", loaded.metadata.keyAlias)
        assertEquals(fingerprint, loaded.metadata.publicKeyFingerprint)
    }
}
