package com.antitheft.common.vault

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class VaultCryptoTest {

    private val vaultManager = VaultCryptoManager()

    private fun generateTestKey(): SecretKey {
        val kgen = KeyGenerator.getInstance("AES")
        kgen.init(256)
        return kgen.generateKey()
    }

    @Test
    fun testAesGcmEncryptDecryptRoundtrip() {
        val key = generateTestKey()
        val secretJson = "{\"imei_1\":\"358291048291823\",\"recovery_code\":\"G-992144\"}"

        val container = vaultManager.encrypt(secretJson, key, keyVersion = 1)
        assertNotNull(container.ciphertextBase64)
        assertEquals(1, container.keyVersion)

        // Validate IV length is 12 bytes
        val ivBytes = Base64.getDecoder().decode(container.ivBase64)
        assertEquals(12, ivBytes.size)

        // Validate Tag length is 16 bytes
        val tagBytes = Base64.getDecoder().decode(container.tagBase64)
        assertEquals(16, tagBytes.size)

        // Decrypt
        val decrypted = vaultManager.decrypt(container, key)
        assertEquals(secretJson, decrypted)
    }

    @Test
    fun testDifferentIvForEveryEncryption() {
        val key = generateTestKey()
        val text = "identical_secret_content"

        val container1 = vaultManager.encrypt(text, key)
        val container2 = vaultManager.encrypt(text, key)

        assertNotEquals(container1.ivBase64, container2.ivBase64)
        assertNotEquals(container1.ciphertextBase64, container2.ciphertextBase64)
    }

    @Test
    fun testTamperedCiphertextThrowsSecurityException() {
        val key = generateTestKey()
        val secret = "critical_insurance_policy_number_9921"
        val container = vaultManager.encrypt(secret, key)

        // Flip one byte in ciphertext
        val ctBytes = Base64.getDecoder().decode(container.ciphertextBase64)
        ctBytes[0] = (ctBytes[0].toInt() xor 0xFF).toByte()
        val tamperedContainer = container.copy(
            ciphertextBase64 = Base64.getEncoder().encodeToString(ctBytes)
        )

        try {
            vaultManager.decrypt(tamperedContainer, key)
            fail("Expected AEADBadTagException for tampered ciphertext")
        } catch (e: Exception) {
            assertTrue("Expected AEADBadTagException or security error, got: ${e.javaClass.name}",
                e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testTamperedTagThrowsSecurityException() {
        val key = generateTestKey()
        val secret = "critical_insurance_policy_number_9921"
        val container = vaultManager.encrypt(secret, key)

        // Flip one byte in tag
        val tagBytes = Base64.getDecoder().decode(container.tagBase64)
        tagBytes[tagBytes.size - 1] = (tagBytes[tagBytes.size - 1].toInt() xor 0xFF).toByte()
        val tamperedContainer = container.copy(
            tagBase64 = Base64.getEncoder().encodeToString(tagBytes)
        )

        try {
            vaultManager.decrypt(tamperedContainer, key)
            fail("Expected AEADBadTagException for tampered tag")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testExportAndImportVaultWithPassphrase() {
        val vaultData = "{\"recovery_codes\":[\"1234-5678\",\"8765-4321\"],\"imei\":\"99000112233\"}"
        val passphrase = "CorrectHorseBatteryStaple123!"

        val exportBlob = vaultManager.exportVaultEncrypted(vaultData, passphrase)
        assertNotNull(exportBlob)
        assertTrue(exportBlob.isNotBlank())

        // Import with correct passphrase
        val imported = vaultManager.importVaultEncrypted(exportBlob, passphrase)
        assertEquals(vaultData, imported)
    }

    @Test
    fun testWrongPassphraseThrowsOnImport() {
        val vaultData = "{\"recovery_codes\":[\"1234-5678\"]}"
        val passphrase = "CorrectPassword123!"
        val wrongPassphrase = "WrongPassword456!"

        val exportBlob = vaultManager.exportVaultEncrypted(vaultData, passphrase)

        try {
            vaultManager.importVaultEncrypted(exportBlob, wrongPassphrase)
            fail("Expected decryption failure with wrong passphrase")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testKeyRotationRoundtrip() {
        val keyV1 = generateTestKey()
        val keyV2 = generateTestKey()
        val secret = "important_serial_number_XYZ"

        // 1. Initial encryption under Key v1
        val itemV1 = vaultManager.encrypt(secret, keyV1, keyVersion = 1)
        assertEquals(1, itemV1.keyVersion)

        // 2. Client decrypts with old Key v1
        val decryptedSecret = vaultManager.decrypt(itemV1, keyV1)
        assertEquals(secret, decryptedSecret)

        // 3. Client re-encrypts under rotated Key v2
        val itemV2 = vaultManager.encrypt(decryptedSecret, keyV2, keyVersion = 2)
        assertEquals(2, itemV2.keyVersion)

        // 4. Verify itemV2 can only be decrypted with Key v2, not Key v1
        val decryptedV2 = vaultManager.decrypt(itemV2, keyV2)
        assertEquals(secret, decryptedV2)

        try {
            vaultManager.decrypt(itemV2, keyV1)
            fail("Old key v1 should fail to decrypt item rotated to key v2")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testVaultKeyHierarchyEnvelopeEncryption() {
        // 1. Generate random 256-bit Data Encryption Key (DEK)
        val dek = vaultManager.generateRandomDataKey()
        val sensitiveData = "{\"title\":\"Master Recovery Key\",\"code\":\"RECOVER-7K9M2W4P\"}"

        // 2. Encrypt vault item using DEK
        val itemContainer = vaultManager.encrypt(sensitiveData, dek)

        // 3. Local Key Encryption Key (KEK) representing hardware Keystore AES-256 key
        val localKeystoreKek = generateTestKey()

        // 4. Remote/Server Key Encryption Key (KEK) derived from user master recovery passphrase via PBKDF2
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val passphraseKek = vaultManager.deriveKeyFromPassphrase("OwnerSuperSecretPassphrase123!", salt, 100_000)

        // 5. Wrap DEK under local Keystore KEK (for zero-latency local unlock)
        val wrappedByLocalKeystore = vaultManager.wrapKey(dek, localKeystoreKek)

        // 6. Wrap DEK under Passphrase KEK (stored on server for cross-device recovery)
        val wrappedByPassphrase = vaultManager.wrapKey(dek, passphraseKek)

        // Verify unwrap path A: Local Hardware Keystore
        val unwrappedDekLocal = vaultManager.unwrapKey(wrappedByLocalKeystore, localKeystoreKek)
        val decryptedWithLocal = vaultManager.decrypt(itemContainer, unwrappedDekLocal)
        assertEquals(sensitiveData, decryptedWithLocal)

        // Verify unwrap path B: Passphrase Restore (e.g. from server backup on fresh phone)
        val unwrappedDekPassphrase = vaultManager.unwrapKey(wrappedByPassphrase, passphraseKek)
        val decryptedWithPassphrase = vaultManager.decrypt(itemContainer, unwrappedDekPassphrase)
        assertEquals(sensitiveData, decryptedWithPassphrase)

        // Verify wrong passphrase fails unwrapping
        val wrongPassphraseKek = vaultManager.deriveKeyFromPassphrase("WrongPassphrase456!", salt, 100_000)
        try {
            vaultManager.unwrapKey(wrappedByPassphrase, wrongPassphraseKek)
            fail("Unwrapping with wrong passphrase KEK must fail authentication")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }
}
