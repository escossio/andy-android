package io.github.escossio.andy.data.deviceidentity

import android.content.Context
import io.github.escossio.andy.core.deviceidentity.DEVICE_IDENTITY_KEY_ALIAS
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityEngine
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityManager
import java.security.SecureRandom

object AndroidDeviceIdentityFactory {
    fun create(context: Context): DeviceIdentityManager {
        val random = SecureRandom()
        return create(
            context = context,
            fileName = DEVICE_IDENTITY_METADATA_FILE_NAME,
            keyAlias = DEVICE_IDENTITY_KEY_ALIAS,
            challengeGenerator = { ByteArray(32).also(random::nextBytes) },
        )
    }

    internal fun create(
        context: Context,
        fileName: String,
        keyAlias: String,
        challengeGenerator: () -> ByteArray,
    ): DeviceIdentityManager = DeviceIdentityEngine(
        metadataRepository = NoBackupDeviceIdentityMetadataRepository(
            context.applicationContext,
            fileName,
        ),
        crypto = AndroidKeystoreDeviceIdentityCrypto(),
        challengeGenerator = challengeGenerator,
        keyAlias = keyAlias,
    )
}
