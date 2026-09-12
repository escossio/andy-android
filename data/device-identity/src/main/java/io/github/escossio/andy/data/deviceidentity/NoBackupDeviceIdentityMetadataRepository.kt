package io.github.escossio.andy.data.deviceidentity

import android.content.Context
import android.util.AtomicFile
import io.github.escossio.andy.core.deviceidentity.DEVICE_IDENTITY_SCHEMA_VERSION
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityMetadataRepository
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityUnavailableReason
import io.github.escossio.andy.core.deviceidentity.DeviceKeyFingerprint
import io.github.escossio.andy.core.deviceidentity.MetadataLoadResult
import io.github.escossio.andy.core.deviceidentity.StoredIdentityMetadata
import io.github.escossio.andy.core.deviceidentity.StoredIdentityState
import org.json.JSONObject
import java.io.File

internal const val DEVICE_IDENTITY_METADATA_FILE_NAME = "device_identity_v1.json"

internal class NoBackupDeviceIdentityMetadataRepository(
    context: Context,
    fileName: String = DEVICE_IDENTITY_METADATA_FILE_NAME,
) : DeviceIdentityMetadataRepository {
    private val file = File(context.noBackupFilesDir, fileName)
    private val atomicFile = AtomicFile(file)

    override fun load(): MetadataLoadResult {
        if (!file.exists()) return MetadataLoadResult.Absent
        return try {
            val json = JSONObject(atomicFile.openRead().bufferedReader().use { it.readText() })
            val schema = json.getInt("schema_version")
            if (schema != DEVICE_IDENTITY_SCHEMA_VERSION) {
                return MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_UNSUPPORTED)
            }
            val state = StoredIdentityState.valueOf(json.getString("state"))
            val alias = json.getString("key_alias")
            val fingerprint = if (state == StoredIdentityState.READY) {
                DeviceKeyFingerprint.parse(json.getString("public_key_fingerprint"))
                    ?: return MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
            } else null
            MetadataLoadResult.Present(StoredIdentityMetadata(schema, state, alias, fingerprint))
        } catch (_: Exception) {
            MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
        }
    }

    override fun writeProvisioning(keyAlias: String) {
        write(
            JSONObject()
                .put("schema_version", DEVICE_IDENTITY_SCHEMA_VERSION)
                .put("state", StoredIdentityState.PROVISIONING.name)
                .put("key_alias", keyAlias),
        )
    }

    override fun writeReady(keyAlias: String, fingerprint: DeviceKeyFingerprint) {
        write(
            JSONObject()
                .put("schema_version", DEVICE_IDENTITY_SCHEMA_VERSION)
                .put("state", StoredIdentityState.READY.name)
                .put("key_alias", keyAlias)
                .put("public_key_fingerprint", fingerprint.value),
        )
    }

    private fun write(json: JSONObject) {
        val output = atomicFile.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}
