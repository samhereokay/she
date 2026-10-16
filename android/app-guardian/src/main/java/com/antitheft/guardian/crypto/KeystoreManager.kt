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

    fun getSecurityInfo(context: android.content.Context? = null): KeystoreSecurityInfo {
        val keyPair = getOrCreateDeviceKeyPair()
        val factory = java.security.KeyFactory.getInstance(keyPair.private.algorithm, CryptoConstants.ANDROID_KEYSTORE)
        val keyInfo = factory.getKeySpec(keyPair.private, android.security.keystore.KeyInfo::class.java)
        val isInside = keyInfo.isInsideSecureHardware
        val securityLevel = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                else -> "UNKNOWN(${keyInfo.securityLevel})"
            }
        } else {
            if (isInside) "HARDWARE_TEE" else "SOFTWARE"
        }
        val hasStrongBox = context?.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE) ?: false
        android.util.Log.i(TAG, "Runtime Keystore Key: alias=$KEY_ALIAS, isInsideSecureHardware=$isInside, securityLevel=$securityLevel, hasStrongBox=$hasStrongBox")
        return KeystoreSecurityInfo(KEY_ALIAS, isInside, securityLevel, hasStrongBox)
    }

    companion object {
        private const val TAG = "KeystoreSecurity"
        private const val KEY_ALIAS = "guardian_device_identity_key"
    }
}

data class KeystoreSecurityInfo(
    val alias: String,
    val isInsideSecureHardware: Boolean,
    val securityLevel: String,
    val hasStrongBoxFeature: Boolean
)
