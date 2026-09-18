package io.github.escossio.andy.data.clientsession

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import io.github.escossio.andy.sdk.clientapi.ClientSessionStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val CLIENT_SESSION_KEY_ALIAS = "andy_client_session_wrap_v1"
internal const val CLIENT_SESSION_FILE_NAME = "client_session_v1.json"

class AndroidKeystoreClientSessionStore(
    context: Context,
    private val fileName: String = CLIENT_SESSION_FILE_NAME,
    private val keyAlias: String = CLIENT_SESSION_KEY_ALIAS,
) : ClientSessionStore {
    private val file = File(context.applicationContext.noBackupFilesDir, fileName)
    private val atomicFile = AtomicFile(file)

    override suspend fun load(): ClientSessionCredential? {
        if (!file.exists()) return null

        var plaintext: ByteArray? = null
        return try {
            val envelope = ClientSessionEnvelopeCodec.decodeEnvelope(
                atomicFile.openRead().use { it.readBytes() },
            ) ?: return purgeAndReturnNull()

            val key = existingKey() ?: return purgeAndReturnNull()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, envelope.iv),
            )
            plaintext = cipher.doFinal(envelope.ciphertext)
            ClientSessionEnvelopeCodec.decodeCredential(requireNotNull(plaintext))
                ?: purgeAndReturnNull()
        } catch (_: Exception) {
            purgeAndReturnNull()
        } finally {
            plaintext?.fill(0)
        }
    }

    override suspend fun save(session: ClientSessionCredential) {
        val plaintext = ClientSessionEnvelopeCodec.encodeCredential(session)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext)
            val envelope = ClientSessionEnvelopeCodec.encodeEnvelope(cipher.iv, ciphertext)
            write(envelope)
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun clear() {
        atomicFile.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!store.containsAlias(keyAlias)) return null
        return store.getKey(keyAlias, null) as? SecretKey
    }

    private fun write(bytes: ByteArray) {
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private suspend fun purgeAndReturnNull(): ClientSessionCredential? {
        clear()
        return null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
