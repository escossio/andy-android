package io.github.escossio.andy.data.deviceidentity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityCrypto
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

internal class AndroidKeystoreDeviceIdentityCrypto : DeviceIdentityCrypto {
    override fun hasKey(keyAlias: String): Boolean = keyStore().containsAlias(keyAlias)

    override fun generateKey(keyAlias: String) {
        check(!hasKey(keyAlias)) { "Device identity key already exists" }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKeyPair()
    }

    override fun publicKeySubjectPublicKeyInfo(keyAlias: String): ByteArray =
        requireNotNull(keyStore().getCertificate(keyAlias)) { "Device identity certificate missing" }
            .publicKey.encoded

    override fun signForSelfTest(keyAlias: String, challenge: ByteArray): ByteArray {
        val privateKey = requireNotNull(keyStore().getKey(keyAlias, null) as? java.security.PrivateKey) {
            "Device identity private key missing"
        }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
    }

    override fun verifySelfTest(keyAlias: String, challenge: ByteArray, signature: ByteArray): Boolean {
        val publicKey = requireNotNull(keyStore().getCertificate(keyAlias)) {
            "Device identity certificate missing"
        }.publicKey
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(signature)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
