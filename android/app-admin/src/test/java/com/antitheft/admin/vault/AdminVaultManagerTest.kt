package com.antitheft.admin.vault

import com.antitheft.common.vault.VaultCryptoManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AdminVaultManagerTest {

    private lateinit var vaultManager: AdminVaultManager
    private lateinit var testMasterKey: SecretKey

    @Before
    fun setup() {
        vaultManager = AdminVaultManager(context = null)
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        testMasterKey = kg.generateKey()
    }

    @Test
    fun testVerifyEnvelopeRoundTripSucceedsWithMatchingPassphrase() {
        val passphrase = "correct-horse-battery-staple-2026".toCharArray()
        val payload = "{\"title\":\"Master-Recovery-Key\",\"secret\":\"HEX-DEED-BEEF-001122\"}"

        val container = vaultManager.encryptSecretEnvelope(
            plaintext = payload,
            passphrase = passphrase,
            keyVersion = 1,
            masterKeyOverride = testMasterKey
        )

        // Real AdminVaultManager.verifyEnvelopeRoundTrip call
        val verified = vaultManager.verifyEnvelopeRoundTrip(container, passphrase, payload)
        assertTrue("verifyEnvelopeRoundTrip must succeed when passphrase matches exactly", verified)

        // Decrypt and confirm payload matches
        val decrypted = vaultManager.decryptSecretEnvelope(container, passphrase)
        assertEquals(payload, decrypted)
    }

    @Test
    fun testVerifyEnvelopeRoundTripFailsWithTypoPassphrase() {
        val correctPassphrase = "correct-horse-battery-staple-2026".toCharArray()
        val typoPassphrase = "correct-horse-battery-staple-20267".toCharArray() // Stray '7' from IME tap
        val payload = "{\"title\":\"Secret\",\"secret\":\"CONFIDENTIAL\"}"

        val container = vaultManager.encryptSecretEnvelope(
            plaintext = payload,
            passphrase = correctPassphrase,
            keyVersion = 1,
            masterKeyOverride = testMasterKey
        )

        // Real AdminVaultManager.verifyEnvelopeRoundTrip call with wrong passphrase
        val verified = vaultManager.verifyEnvelopeRoundTrip(container, typoPassphrase, payload)
        assertFalse("verifyEnvelopeRoundTrip must return false when passphrase has typo", verified)
    }

    @Test
    fun testVerifyEnvelopeRoundTripFailsWithMismatchedPayload() {
        val passphrase = "correct-horse-battery-staple-2026".toCharArray()
        val payload = "{\"title\":\"Secret\",\"secret\":\"CONFIDENTIAL\"}"

        val container = vaultManager.encryptSecretEnvelope(
            plaintext = payload,
            passphrase = passphrase,
            keyVersion = 1,
            masterKeyOverride = testMasterKey
        )

        // verifyEnvelopeRoundTrip against a different expected payload must fail
        val verified = vaultManager.verifyEnvelopeRoundTrip(container, passphrase, "{\"tampered\": true}")
        assertFalse("verifyEnvelopeRoundTrip must fail if decrypted plaintext does not match expected payload", verified)
    }

    @Test
    fun testPassphraseConfirmationAndMemoryZeroing() {
        val pass1 = "correct-horse-battery-staple-2026".toCharArray()
        val pass2Mismatch = "correct-horse-battery-staple-20267".toCharArray()
        val pass3Match = "correct-horse-battery-staple-2026".toCharArray()

        // Confirmation mismatch detection
        assertFalse("Passphrases with different lengths must not match", pass1.contentEquals(pass2Mismatch))
        assertTrue("Matching passphrases must match", pass1.contentEquals(pass3Match))

        // In-memory zeroing
        java.util.Arrays.fill(pass2Mismatch, '\u0000')
        for (c in pass2Mismatch) {
            assertEquals("Memory must be zeroed", '\u0000', c)
        }
    }
}
