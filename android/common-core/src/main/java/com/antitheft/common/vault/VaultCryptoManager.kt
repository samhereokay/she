package com.antitheft.common.vault

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Data container representing a client-side encrypted vault item.
 * Strictly zero-knowledge: only ciphertext, IV, and tag leave the device.
 */
data class VaultEncryptedContainer(
    val ciphertextBase64: String,
    val ivBase64: String,
    val tagBase64: String,
    val keyVersion: Long = 1
)

/**
 * Client-Side End-to-End Cryptographic Manager for Recovery Vault.
 * Implements AES-256-GCM authenticated encryption and PBKDF2 paper backup export/import.
 */
class VaultCryptoManager {

    private val secureRandom = SecureRandom()

    /**
     * Encrypt arbitrary string content (recovery codes, credentials, device identifiers)
     * using AES-256-GCM. Generates a fresh 12-byte IV for every encryption call.
     */
    fun encrypt(plaintext: String, secretKey: SecretKey, keyVersion: Long = 1): VaultEncryptedContainer {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        val encryptedWithTag = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        
        // Java AES/GCM appends 16-byte tag to the ciphertext
        val tagOffset = encryptedWithTag.size - GCM_TAG_LENGTH_BYTES
        val ciphertext = encryptedWithTag.copyOfRange(0, tagOffset)
        val tag = encryptedWithTag.copyOfRange(tagOffset, encryptedWithTag.size)

        val b64 = Base64.getEncoder()
        return VaultEncryptedContainer(
            ciphertextBase64 = b64.encodeToString(ciphertext),
            ivBase64 = b64.encodeToString(iv),
            tagBase64 = b64.encodeToString(tag),
            keyVersion = keyVersion
        )
    }

    /**
     * Decrypt an encrypted container. Reconstructs standard AES-GCM ciphertext + tag.
     * Throws javax.crypto.AEADBadTagException if any byte of ciphertext or tag has been tampered with.
     */
    fun decrypt(container: VaultEncryptedContainer, secretKey: SecretKey): String {
        val b64Decoder = Base64.getDecoder()
        val ciphertext = b64Decoder.decode(container.ciphertextBase64)
        val iv = b64Decoder.decode(container.ivBase64)
        val tag = b64Decoder.decode(container.tagBase64)

        if (iv.size != GCM_IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Invalid IV length: ${iv.size} bytes (required $GCM_IV_LENGTH_BYTES)")
        }
        if (tag.size != GCM_TAG_LENGTH_BYTES) {
            throw IllegalArgumentException("Invalid tag length: ${tag.size} bytes (required $GCM_TAG_LENGTH_BYTES)")
        }

        // Recombine ciphertext and tag for Java GCM cipher
        val combined = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(combined)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    /**
     * Export all vault items as an encrypted paper backup / QR string protected by user passphrase.
     * Uses PBKDF2WithHmacSHA256 with 100,000 iterations and 16-byte random salt.
     */
    fun exportVaultEncrypted(vaultJson: String, passphrase: CharArray): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)

        val derivedKey = deriveKeyFromPassphrase(passphrase, salt, PBKDF2_ITERATIONS)
        val container = encrypt(vaultJson, derivedKey, keyVersion = 1)

        val b64 = Base64.getEncoder()
        val exportObj = JSONObject().apply {
            put("version", 1)
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", PBKDF2_ITERATIONS)
            put("salt", b64.encodeToString(salt))
            put("iv", container.ivBase64)
            put("tag", container.tagBase64)
            put("ciphertext", container.ciphertextBase64)
        }
        return b64.encodeToString(exportObj.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun exportVaultEncrypted(vaultJson: String, passphrase: String): String {
        val chars = passphrase.toCharArray()
        try {
            return exportVaultEncrypted(vaultJson, chars)
        } finally {
            java.util.Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Import and restore an encrypted paper backup string using the user's recovery passphrase.
     */
    fun importVaultEncrypted(exportBase64: String, passphrase: CharArray): String {
        val jsonStr = String(Base64.getDecoder().decode(exportBase64.trim()), StandardCharsets.UTF_8)
        val obj = JSONObject(jsonStr)

        val iterations = obj.getInt("iterations")
        val salt = Base64.getDecoder().decode(obj.getString("salt"))
        val iv = obj.getString("iv")
        val tag = obj.getString("tag")
        val ciphertext = obj.getString("ciphertext")

        val derivedKey = deriveKeyFromPassphrase(passphrase, salt, iterations)
        val container = VaultEncryptedContainer(
            ciphertextBase64 = ciphertext,
            ivBase64 = iv,
            tagBase64 = tag,
            keyVersion = 1
        )
        return decrypt(container, derivedKey)
    }

    fun importVaultEncrypted(exportBase64: String, passphrase: String): String {
        val chars = passphrase.toCharArray()
        try {
            return importVaultEncrypted(exportBase64, chars)
        } finally {
            java.util.Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Derives a 256-bit AES secret key from a passphrase and salt.
     * Clears PBEKeySpec memory immediately after key derivation.
     */
    fun deriveKeyFromPassphrase(passphrase: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val tmp = factory.generateSecret(spec)
            return SecretKeySpec(tmp.encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey {
        val chars = passphrase.toCharArray()
        try {
            return deriveKeyFromPassphrase(chars, salt, iterations)
        } finally {
            java.util.Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Generates a high-entropy 256-bit AES Data Encryption Key (DEK).
     */
    fun generateRandomDataKey(): SecretKey {
        val keyBytes = ByteArray(32)
        secureRandom.nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Generates a cryptographically secure random salt for PBKDF2 key derivation.
     */
    fun generateRandomSalt(lengthBytes: Int = 16): ByteArray {
        val salt = ByteArray(lengthBytes)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * Envelope Key Wrapping: Encrypts a Data Encryption Key (DEK) with a Key Encryption Key (KEK) using AES-256-GCM.
     */
    fun wrapKey(dek: SecretKey, kek: SecretKey, keyVersion: Long = 1): VaultEncryptedContainer {
        val rawDekBase64 = Base64.getEncoder().encodeToString(dek.encoded)
        return encrypt(rawDekBase64, kek, keyVersion)
    }

    /**
     * Envelope Key Unwrapping: Decrypts a wrapped Data Encryption Key (DEK) with a Key Encryption Key (KEK).
     */
    fun unwrapKey(wrappedDek: VaultEncryptedContainer, kek: SecretKey): SecretKey {
        val rawDekBase64 = decrypt(wrappedDek, kek)
        val rawBytes = Base64.getDecoder().decode(rawDekBase64)
        return SecretKeySpec(rawBytes, "AES")
    }

    companion object {
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BYTES = 16
        const val GCM_TAG_LENGTH_BITS = 128
        const val PBKDF2_ITERATIONS = 600_000
    }
}
