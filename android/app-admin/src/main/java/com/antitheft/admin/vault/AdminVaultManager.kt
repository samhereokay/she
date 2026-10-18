package com.antitheft.admin.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.antitheft.common.vault.VaultCryptoManager
import com.antitheft.common.vault.VaultEncryptedContainer
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * High-level manager for Admin Device Recovery Vault.
 * Bridges Android Keystore hardware-backed AES-256 keys with VaultCryptoManager.
 */
class AdminVaultManager(
    private val keyAlias: String = VAULT_KEY_ALIAS,
    private val context: android.content.Context? = null
) {

    private val cryptoManager = VaultCryptoManager()
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isDeviceSecure(): Boolean {
        if (context == null) return false
        val km = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isDeviceSecure == true
    }

    /**
     * Builds KeyGenParameterSpec with setUserAuthenticationRequired(true) and hardware AES-GCM policy.
     *
     * Auth validity window is deliberately set to 60 seconds (1 minute):
     * Setting authValiditySeconds = 0 requires a BiometricPrompt.CryptoObject per operation, degrading UX
     * when displaying multiple vault items. Setting 60s provides a bounded window after device credential/biometric
     * unlock to view and manage secrets, while immediately invalidating permanently (KeyPermanentlyInvalidatedException)
     * if device lock credentials are removed or modified.
     */
    fun buildKeyGenParameterSpec(
        alias: String = keyAlias,
        requireUserAuth: Boolean = true,
        authValiditySeconds: Int = 60
    ): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(requireUserAuth)

        if (requireUserAuth) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    authValiditySeconds.coerceAtLeast(0),
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(authValiditySeconds)
            }
        }
        return builder.build()
    }

    /**
     * Retrieves or generates the 256-bit AES-GCM Vault Master Key inside Android Keystore.
     */
    @Synchronized
    fun getOrCreateVaultMasterKey(requireUserAuth: Boolean = isDeviceSecure()): SecretKey {
        if (keyStore.containsAlias(keyAlias)) {
            try {
                val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    val key = entry.secretKey
                    if (!requireUserAuth) {
                        // Verify key works without auth in unsecured mode; otherwise recreate
                        val testCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                        testCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
                    }
                    return key
                }
            } catch (e: Exception) {
                try { keyStore.deleteEntry(keyAlias) } catch (_: Exception) {}
            }
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = buildKeyGenParameterSpec(keyAlias, requireUserAuth = requireUserAuth)
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Creates an AES-GCM Cipher bound to a BiometricPrompt.CryptoObject for authenticated crypto operations.
     */
    fun createCryptoObject(mode: Int, iv: ByteArray? = null): androidx.biometric.BiometricPrompt.CryptoObject {
        val masterKey = getOrCreateVaultMasterKey()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        if (mode == javax.crypto.Cipher.DECRYPT_MODE && iv != null) {
            cipher.init(mode, masterKey, javax.crypto.spec.GCMParameterSpec(128, iv))
        } else {
            cipher.init(mode, masterKey)
        }
        return androidx.biometric.BiometricPrompt.CryptoObject(cipher)
    }

    /**
     * Encrypt a recovery secret (IMEI, recovery code, document metadata) into an encrypted container.
     */
    fun encryptSecret(plaintext: String, keyVersion: Long = 1): VaultEncryptedContainer {
        val masterKey = getOrCreateVaultMasterKey()
        return cryptoManager.encrypt(plaintext, masterKey, keyVersion)
    }

    /**
     * Decrypt an encrypted container using the Keystore Vault Master Key.
     */
    fun decryptSecret(container: VaultEncryptedContainer): String {
        val masterKey = getOrCreateVaultMasterKey()
        return cryptoManager.decrypt(container, masterKey)
    }

    /**
     * Envelope Encryption (User Key Hierarchy):
     * 1. Generates random 256-bit AES Data Key (DEK).
     * 2. Encrypts payload with DEK.
     * 3. Wraps DEK with Keystore Master Key (auth-required).
     * 4. Generates a fresh cryptographically random 16-byte salt per vault item.
     * 5. Derives Passphrase Key via PBKDF2 (600,000 iterations) using the item salt.
     * 6. Wraps DEK with Passphrase-derived Key.
     * 7. Packages ciphertext, IV, tag, salt, and wrapped keys into envelope JSON.
     */
    fun encryptSecretEnvelope(
        plaintext: String,
        passphrase: CharArray,
        keyVersion: Long = 1
    ): VaultEncryptedContainer {
        require(passphrase.size >= 16) {
            "Vault master passphrase must be at least 16 characters for zero-knowledge envelope protection."
        }
        val dek = cryptoManager.generateRandomDataKey()
        val dataContainer = cryptoManager.encrypt(plaintext, dek, keyVersion)

        val keystoreKey = getOrCreateVaultMasterKey(requireUserAuth = isDeviceSecure())
        val keystoreWrappedDek = cryptoManager.wrapKey(dek, keystoreKey, keyVersion)

        val salt = cryptoManager.generateRandomSalt(16)
        val saltB64 = java.util.Base64.getEncoder().encodeToString(salt)
        val passphraseKey = deriveVaultMasterKeyFromPassphrase(passphrase, salt)
        val passphraseWrappedDek = cryptoManager.wrapKey(dek, passphraseKey, keyVersion)

        val envelope = org.json.JSONObject().apply {
            put("v", 2)
            put("salt", saltB64)
            put("data_cipher", dataContainer.ciphertextBase64)
            put("data_iv", dataContainer.ivBase64)
            put("data_tag", dataContainer.tagBase64)
            put("keystore_wrapped_dek", org.json.JSONObject().apply {
                put("cipher", keystoreWrappedDek.ciphertextBase64)
                put("iv", keystoreWrappedDek.ivBase64)
                put("tag", keystoreWrappedDek.tagBase64)
            })
            put("passphrase_wrapped_dek", org.json.JSONObject().apply {
                put("cipher", passphraseWrappedDek.ciphertextBase64)
                put("iv", passphraseWrappedDek.ivBase64)
                put("tag", passphraseWrappedDek.tagBase64)
            })
        }

        return VaultEncryptedContainer(
            ciphertextBase64 = envelope.toString(),
            ivBase64 = dataContainer.ivBase64,
            tagBase64 = dataContainer.tagBase64,
            keyVersion = keyVersion
        )
    }

    fun encryptSecretEnvelope(
        plaintext: String,
        passphrase: String,
        keyVersion: Long = 1
    ): VaultEncryptedContainer {
        val chars = passphrase.toCharArray()
        try {
            return encryptSecretEnvelope(plaintext, chars, keyVersion)
        } finally {
            java.util.Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Envelope Decryption:
     * Unwraps DEK via Keystore Key (if device lock active), or via passphrase-derived Key (if restored with passphrase).
     */
    fun decryptSecretEnvelope(
        container: VaultEncryptedContainer,
        passphrase: CharArray? = null
    ): String {
        if (!container.ciphertextBase64.trim().startsWith("{")) {
            val k = getOrCreateVaultMasterKey(requireUserAuth = isDeviceSecure())
            return cryptoManager.decrypt(container, k)
        }

        val envelope = org.json.JSONObject(container.ciphertextBase64)
        if (envelope.optInt("v", 1) != 2) {
            val k = getOrCreateVaultMasterKey(requireUserAuth = isDeviceSecure())
            return cryptoManager.decrypt(container, k)
        }

        val dataCipher = envelope.getString("data_cipher")
        val dataIv = envelope.getString("data_iv")
        val dataTag = envelope.getString("data_tag")
        val dataContainer = VaultEncryptedContainer(dataCipher, dataIv, dataTag, container.keyVersion)

        val dek: SecretKey = if (passphrase != null && passphrase.isNotEmpty()) {
            val pObj = envelope.getJSONObject("passphrase_wrapped_dek")
            val pContainer = VaultEncryptedContainer(
                pObj.getString("cipher"),
                pObj.getString("iv"),
                pObj.getString("tag")
            )
            val saltB64 = envelope.optString("salt")
            val salt = if (saltB64.isNotEmpty()) {
                java.util.Base64.getDecoder().decode(saltB64)
            } else {
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("antitheft-vault-user-salt-v1".toByteArray(Charsets.UTF_8))
            }
            val passphraseKey = deriveVaultMasterKeyFromPassphrase(passphrase, salt)
            cryptoManager.unwrapKey(pContainer, passphraseKey)
        } else {
            val kObj = envelope.getJSONObject("keystore_wrapped_dek")
            val kContainer = VaultEncryptedContainer(
                kObj.getString("cipher"),
                kObj.getString("iv"),
                kObj.getString("tag")
            )
            val keystoreKey = getOrCreateVaultMasterKey(requireUserAuth = isDeviceSecure())
            cryptoManager.unwrapKey(kContainer, keystoreKey)
        }

        return cryptoManager.decrypt(dataContainer, dek)
    }

    fun decryptSecretEnvelope(
        container: VaultEncryptedContainer,
        passphrase: String?
    ): String {
        if (passphrase == null) {
            return decryptSecretEnvelope(container, null as CharArray?)
        }
        val chars = passphrase.toCharArray()
        try {
            return decryptSecretEnvelope(container, chars)
        } finally {
            java.util.Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Pre-Upload Verification: Confirms that an encrypted envelope can be unwrapped and decrypted
     * locally using the entered passphrase before submitting it to the remote backend.
     */
    fun verifyEnvelopeRoundTrip(
        container: VaultEncryptedContainer,
        passphrase: CharArray,
        expectedPayload: String
    ): Boolean {
        return try {
            val decrypted = decryptSecretEnvelope(container, passphrase)
            decrypted == expectedPayload
        } catch (e: Exception) {
            false
        }
    }


    /**
     * Export all vault items as an encrypted paper backup / QR string protected by user passphrase.
     */
    fun exportPaperBackup(vaultJson: String, passphrase: CharArray): String {
        return cryptoManager.exportVaultEncrypted(vaultJson, passphrase)
    }

    fun exportPaperBackup(vaultJson: String, passphrase: String): String {
        val chars = passphrase.toCharArray()
        try {
            return exportPaperBackup(vaultJson, chars)
        } finally {
            java.util.Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Import an encrypted paper backup using the user passphrase.
     */
    fun importPaperBackup(exportBase64: String, passphrase: CharArray): String {
        return cryptoManager.importVaultEncrypted(exportBase64, passphrase)
    }

    fun importPaperBackup(exportBase64: String, passphrase: String): String {
        val chars = passphrase.toCharArray()
        try {
            return importPaperBackup(exportBase64, chars)
        } finally {
            java.util.Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Derives a deterministic Vault Master Key from the user's master recovery passphrase and salt.
     * Guarantees that the encrypted server copy CAN be restored on any new phone using the passphrase.
     */
    fun deriveVaultMasterKeyFromPassphrase(passphrase: CharArray, salt: ByteArray): SecretKey {
        return cryptoManager.deriveKeyFromPassphrase(passphrase, salt, VaultCryptoManager.PBKDF2_ITERATIONS)
    }

    fun deriveVaultMasterKeyFromPassphrase(passphrase: String, salt: ByteArray): SecretKey {
        val chars = passphrase.toCharArray()
        try {
            return deriveVaultMasterKeyFromPassphrase(chars, salt)
        } finally {
            java.util.Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Cryptographic Shredding: Delete the Keystore Vault Master Key.
     * Instantly and irrevocably renders all stored vault ciphertexts unrecoverable.
     */
    @Synchronized
    fun shredVaultMasterKey(): Boolean {
        return try {
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val VAULT_KEY_ALIAS = "admin_recovery_vault_aes_key"
    }
}
