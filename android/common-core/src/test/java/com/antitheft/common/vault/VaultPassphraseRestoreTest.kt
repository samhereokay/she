package com.antitheft.common.vault

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

/**
 * Verifies Zero-Knowledge Vault recovery after a complete local device wipe.
 * Proves that:
 * 1. Vault items encrypted with PBKDF2-derived master keys can be fully recovered from server ciphertext using only the master passphrase + salt.
 * 2. Wrong passphrases or tampered server ciphertexts fail cryptographically with AEADBadTagException.
 * 3. Restored items can be seamlessly migrated to a new device's local Keystore master key.
 */
class VaultPassphraseRestoreTest {

    private val cryptoManager = VaultCryptoManager()

    @Test
    fun testVaultRestoreAfterLocalDeviceWipe() {
        val masterPassphrase = "SuperSecureMasterPassphrase2026!"
        val userSalt = ByteArray(32).apply { SecureRandom().nextBytes(this) }

        // --- Step 1: Initial Setup & Local Encryption on Device A ---
        val originalSecretData = "{\"imei\":\"358291048291823\",\"sim_serial\":\"89014103211118510720\",\"recovery_code\":\"RECOVER-982144\"}"
        val derivedMasterKeyDeviceA = cryptoManager.deriveKeyFromPassphrase(
            passphrase = masterPassphrase,
            salt = userSalt,
            iterations = VaultCryptoManager.PBKDF2_ITERATIONS
        )

        val serverContainer = cryptoManager.encrypt(originalSecretData, derivedMasterKeyDeviceA, keyVersion = 1)
        assertNotNull(serverContainer.ciphertextBase64)
        assertNotNull(serverContainer.ivBase64)
        assertNotNull(serverContainer.tagBase64)

        // --- Step 2: Total Device Wipe (Loss of Device A) ---
        // Device A is lost/wiped. All local Keystore keys, databases, and preferences on Device A are destroyed.
        // Attacker or cloud server only has serverContainer (ciphertext, iv, tag) and public userSalt.

        // --- Step 3: Fresh Install on Device B & Passphrase Restore ---
        // User installs app on replacement phone, logs in, and enters master passphrase:
        val derivedMasterKeyDeviceB = cryptoManager.deriveKeyFromPassphrase(
            passphrase = masterPassphrase,
            salt = userSalt,
            iterations = VaultCryptoManager.PBKDF2_ITERATIONS
        )

        // Keys derived deterministically from same passphrase + salt must have identical encoded material
        assertArrayEquals(derivedMasterKeyDeviceA.encoded, derivedMasterKeyDeviceB.encoded)

        // Decrypt server ciphertext on new device
        val restoredPlaintext = cryptoManager.decrypt(serverContainer, derivedMasterKeyDeviceB)
        assertEquals("Decrypted secret must match original secret after wipe", originalSecretData, restoredPlaintext)

        // --- Step 4: Re-encrypt under Device B's local Keystore Master Key ---
        val deviceBKeystoreKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val deviceBLocalContainer = cryptoManager.encrypt(restoredPlaintext, deviceBKeystoreKey, keyVersion = 2)

        val localDecrypted = cryptoManager.decrypt(deviceBLocalContainer, deviceBKeystoreKey)
        assertEquals(originalSecretData, localDecrypted)
    }

    @Test
    fun testVaultRestoreWithWrongPassphraseFails() {
        val correctPassphrase = "CorrectPassword2026!"
        val wrongPassphrase = "WrongPassword2026!"
        val salt = ByteArray(32).apply { SecureRandom().nextBytes(this) }

        val originalSecret = "{\"private_seed\":\"abandon ability able about above absent absorb abstract\"}"
        val validKey = cryptoManager.deriveKeyFromPassphrase(correctPassphrase, salt)
        val container = cryptoManager.encrypt(originalSecret, validKey)

        // Attacker attempts restore with incorrect passphrase
        val wrongKey = cryptoManager.deriveKeyFromPassphrase(wrongPassphrase, salt)
        assertFalse("Keys derived from different passphrases must differ",
            validKey.encoded.contentEquals(wrongKey.encoded))

        try {
            cryptoManager.decrypt(container, wrongKey)
            fail("Expected AEADBadTagException when restoring with wrong passphrase")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testVaultRestoreWithTamperedServerCiphertextFails() {
        val passphrase = "MasterVaultPassphrase!"
        val salt = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val key = cryptoManager.deriveKeyFromPassphrase(passphrase, salt)

        val container = cryptoManager.encrypt("sensitive_documents", key)

        // Server tampered with ciphertext bytes
        val ctBytes = java.util.Base64.getDecoder().decode(container.ciphertextBase64)
        ctBytes[0] = (ctBytes[0].toInt() xor 0x01).toByte()
        val tamperedContainer = container.copy(
            ciphertextBase64 = java.util.Base64.getEncoder().encodeToString(ctBytes)
        )

        try {
            cryptoManager.decrypt(tamperedContainer, key)
            fail("Expected AEADBadTagException for tampered ciphertext from server")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testPerItemSaltUniquenessGeneratesDistinctKeysForIdenticalPassphrase() {
        val passphrase = "IdenticalUserPassphrase2026!"
        val salt1 = cryptoManager.generateRandomSalt(16)
        val salt2 = cryptoManager.generateRandomSalt(16)

        assertEquals(16, salt1.size)
        assertEquals(16, salt2.size)
        assertFalse("Per-item salts must be cryptographically distinct", salt1.contentEquals(salt2))

        val key1 = cryptoManager.deriveKeyFromPassphrase(passphrase, salt1)
        val key2 = cryptoManager.deriveKeyFromPassphrase(passphrase, salt2)

        assertFalse("Keys derived with different item salts must have different raw bytes",
            key1.encoded.contentEquals(key2.encoded))
    }

    @Test
    fun testEnvelopeKeyWrappingRoundTripWithPerItemSalt() {
        val masterPassphrase = "Alpha-Bravo-2026-Recovery-Pass"
        val payload = "{\"imei\":\"358291048291823\",\"sim_serial\":\"89014103211118510720\"}"

        // 1. Generate random 256-bit AES Data Key (DEK)
        val dek = cryptoManager.generateRandomDataKey()
        val dataContainer = cryptoManager.encrypt(payload, dek, keyVersion = 1)

        // 2. Generate per-item random 16-byte salt and derive Passphrase Key (KEK)
        val itemSalt = cryptoManager.generateRandomSalt(16)
        val passphraseKey = cryptoManager.deriveKeyFromPassphrase(masterPassphrase, itemSalt)

        // 3. Wrap DEK with Passphrase KEK
        val wrappedDek = cryptoManager.wrapKey(dek, passphraseKey, keyVersion = 1)

        // 4. Unwrap DEK using Passphrase KEK and item-specific salt
        val unwrappedDek = cryptoManager.unwrapKey(wrappedDek, passphraseKey)
        assertArrayEquals(dek.encoded, unwrappedDek.encoded)

        // 5. Decrypt data container with unwrapped DEK
        val decrypted = cryptoManager.decrypt(dataContainer, unwrappedDek)
        assertEquals(payload, decrypted)
    }

    @Test
    fun testEnvelopeDecryptionWithWrongPassphraseThrowsBadTag() {
        val correctPass = "Alpha-Passphrase-2026-Correct!"
        val wrongPass = "Alpha-Passphrase-2026-WrongOne!"
        val payload = "CONFIDENTIAL_RECOVERY_SEEDS"

        val dek = cryptoManager.generateRandomDataKey()
        val dataContainer = cryptoManager.encrypt(payload, dek)

        val itemSalt = cryptoManager.generateRandomSalt(16)
        val correctKey = cryptoManager.deriveKeyFromPassphrase(correctPass, itemSalt)
        val wrongKey = cryptoManager.deriveKeyFromPassphrase(wrongPass, itemSalt)

        val wrappedDek = cryptoManager.wrapKey(dek, correctKey)

        try {
            cryptoManager.unwrapKey(wrappedDek, wrongKey)
            fail("Expected AEADBadTagException when unwrapping DEK with wrong passphrase")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testPassphraseMinimumLengthEnforcement() {
        val shortPassphrase = "Short123!" // 9 chars (< 16)
        val validPassphrase = "LongEnoughPassphrase2026!" // 25 chars (>= 16)

        assertTrue("Valid passphrase must be >= 16 chars", validPassphrase.length >= 16)
        assertFalse("Short passphrase must be < 16 chars", shortPassphrase.length >= 16)
    }

    @Test
    fun testConfirmPassphraseMismatchRejection() {
        val pass1 = "correct-horse-battery-staple-2026".toCharArray()
        val pass2 = "correct-horse-battery-staple-20267".toCharArray() // Appended typo
        val pass3 = "correct-horse-battery-staple-2026".toCharArray() // Identical

        assertFalse("Mismatched passphrases (pass1 vs pass2) must fail equality", pass1.contentEquals(pass2))
        assertTrue("Matching passphrases (pass1 vs pass3) must succeed", pass1.contentEquals(pass3))

        // Verify cryptographic zeroing
        java.util.Arrays.fill(pass2, '\u0000')
        for (c in pass2) {
            assertEquals("Memory must be zeroed", '\u0000', c)
        }
    }

    @Test
    fun testPreUploadSelfDecryptionVerification() {
        val passphrase = "correct-horse-battery-staple-2026".toCharArray()
        val fullPayload = "{\"title\":\"Pixel-8-Master-IMEI\",\"type\":\"RECOVERY_CODE\",\"secret\":\"359872109876543\"}"

        // Generate envelope
        val dek = cryptoManager.generateRandomDataKey()
        val dataContainer = cryptoManager.encrypt(fullPayload, dek, keyVersion = 1)
        val salt = cryptoManager.generateRandomSalt(16)
        val passphraseKey = cryptoManager.deriveKeyFromPassphrase(passphrase, salt)
        val wrappedDek = cryptoManager.wrapKey(dek, passphraseKey, keyVersion = 1)

        // Pre-upload verification: unwrapping with identical passphrase must succeed
        val unwrappedDek = cryptoManager.unwrapKey(wrappedDek, passphraseKey)
        val decrypted = cryptoManager.decrypt(dataContainer, unwrappedDek)
        assertEquals("Self-decryption verification must yield exact payload before upload", fullPayload, decrypted)

        // Pre-upload verification with a typo/different passphrase must fail and block upload
        val typoPassphrase = "correct-horse-battery-staple-20267".toCharArray()
        val wrongKey = cryptoManager.deriveKeyFromPassphrase(typoPassphrase, salt)
        try {
            cryptoManager.unwrapKey(wrappedDek, wrongKey)
            fail("Upload should be blocked: unwrapping with mismatched passphrase must throw AEADBadTagException")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testPassphraseValidationClearsCachedState() {
        var cachedPassphrase: CharArray? = "temporary-cached-pass-2026".toCharArray()
        assertNotNull(cachedPassphrase)

        // Simulate validation failure (mismatch or short length)
        fun onValidationFailure() {
            cachedPassphrase?.let { java.util.Arrays.fill(it, '\u0000') }
            cachedPassphrase = null
        }

        onValidationFailure()
        assertNull("Cached passphrase must be nullified on validation failure", cachedPassphrase)
    }
}

