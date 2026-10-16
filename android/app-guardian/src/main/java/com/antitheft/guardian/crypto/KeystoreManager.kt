package com.antitheft.guardian.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.antitheft.common.crypto.CryptoConstants
import com.antitheft.common.crypto.EcdsaSigner
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Manages per-device ECDSA P-256 hardware Keystore keypairs.
 */
class KeystoreManager {

    private val keyStore = KeyStore.getInstance(CryptoConstants.ANDROID_KEYSTORE).apply {
        load(null)
    }

    fun getOrCreateDeviceKeyPair(): KeyPair {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateKeyPair()
        }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return KeyPair(publicKey, privateKey)
    }

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            CryptoConstants.ANDROID_KEYSTORE
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(CryptoConstants.EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }

    fun signData(dataBytes: ByteArray): ByteArray {
        val keyPair = getOrCreateDeviceKeyPair()
        return EcdsaSigner.signData(keyPair.private, dataBytes)
    }

    fun getPublicKeyPem(): String {
        val publicKey = getOrCreateDeviceKeyPair().public
        val encoded = Base64.getEncoder().encodeToString(publicKey.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----"
    }

    companion object {
        private const val KEY_ALIAS = "guardian_device_identity_key"
    }
}
