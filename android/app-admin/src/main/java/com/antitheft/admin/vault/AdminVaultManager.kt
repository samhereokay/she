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
    private val keyAlias: String = VAULT_KEY_ALIAS
) {

    private val cryptoManager = VaultCryptoManager()
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * Builds KeyGenParameterSpec with setUserAuthenticationRequired(true) and hardware AES-GCM policy.
     */
    fun buildKeyGenParameterSpec(
        alias: String = keyAlias,
        requireUserAuth: Boolean = true,
        authValiditySeconds: Int = -1
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
    fun getOrCreateVaultMasterKey(requireUserAuth: Boolean = false): SecretKey {
        if (keyStore.containsAlias(keyAlias)) {
            val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
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
     * Export all vault items as an encrypted paper backup / QR string protected by user passphrase.
     */
    fun exportPaperBackup(vaultJson: String, passphrase: String): String {
        return cryptoManager.exportVaultEncrypted(vaultJson, passphrase)
    }

    /**
     * Import an encrypted paper backup using the user passphrase.
     */
    fun importPaperBackup(exportBase64: String, passphrase: String): String {
        return cryptoManager.importVaultEncrypted(exportBase64, passphrase)
    }

    /**
     * Derives a deterministic Vault Master Key from the user's master recovery passphrase and salt.
     * Guarantees that the encrypted server copy CAN be restored on any new phone using the passphrase.
     */
    fun deriveVaultMasterKeyFromPassphrase(passphrase: String, salt: ByteArray): SecretKey {
        return cryptoManager.deriveKeyFromPassphrase(passphrase, salt, VaultCryptoManager.PBKDF2_ITERATIONS)
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
