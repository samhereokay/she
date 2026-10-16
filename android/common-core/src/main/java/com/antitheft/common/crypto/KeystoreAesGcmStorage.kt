package com.antitheft.common.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Robust hardware-backed AES-256-GCM storage replacing deprecated EncryptedSharedPreferences.
 * Uses AndroidKeyStore directly to avoid library deprecation and OEM-specific KeyStore corruption bugs.
 */
class KeystoreAesGcmStorage(
    context: Context,
    prefsName: String,
    private val keyAlias: String = "antitheft_master_aes_gcm_key"
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(CryptoConstants.ANDROID_KEYSTORE).apply { load(null) }

    init {
        ensureSecretKeyExists()
    }

    private fun ensureSecretKeyExists() {
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                CryptoConstants.ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    fun putEncryptedString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

        // Prepend IV to ciphertext (12 bytes IV + ciphertext)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        val encoded = Base64.getEncoder().encodeToString(combined)
        prefs.edit().putString(key, encoded).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun getDecryptedString(key: String, defaultValue: String? = null): String? {
        val encoded = prefs.getString(key, null) ?: return defaultValue
        return try {
            val combined = Base64.getDecoder().decode(encoded)
            if (combined.size < GCM_IV_LENGTH_BYTES) return defaultValue

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            val ciphertext = ByteArray(combined.size - GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertext.size)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            defaultValue
        }
    }

    companion object {
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
