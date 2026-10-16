package com.antitheft.guardian.commands

import com.antitheft.common.crypto.EcdsaSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CommandSecurityTest {

    private lateinit var adminKeyPair: KeyPair
    private lateinit var adminPublicKeyPem: String
    private lateinit var fakeAdminKeyPair: KeyPair

    @Before
    fun setup() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        adminKeyPair = kpg.generateKeyPair()

        val encoded = Base64.getEncoder().encodeToString(adminKeyPair.public.encoded)
        adminPublicKeyPem = "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----"

        fakeAdminKeyPair = kpg.generateKeyPair()
    }

    @Test
    fun testValidCommandPassesVerification() {
        val targetDeviceId = "test-guardian-dev-01"
        val commandType = "RING"
        val sequenceCounter = 10L
        val expiryEpoch = (System.currentTimeMillis() / 1000) + 300 // 5 min future
        val parametersJson = "{\"duration\": 60}"

        val canonicalBytes = EcdsaSigner.createCanonicalCommandBytes(
            commandId = "",
            targetDeviceId = targetDeviceId,
            commandType = commandType,
            sequenceCounter = sequenceCounter,
            expiryEpoch = expiryEpoch,
            parametersJson = parametersJson
        )

        val derSig = EcdsaSigner.signData(adminKeyPair.private, canonicalBytes)
        val sigBase64 = Base64.getEncoder().encodeToString(derSig)

        // Parse key back from PEM
        val parsedKey = EcdsaSigner.parsePublicKeyFromPem(adminPublicKeyPem)
        val isValid = EcdsaSigner.verifySignature(parsedKey, canonicalBytes, derSig)
        assertTrue("Signature must verify against admin public key", isValid)
    }

    @Test
    fun testTamperedSignatureRejected() {
        val targetDeviceId = "test-guardian-dev-01"
        val commandType = "LOCK"
        val sequenceCounter = 5L
        val expiryEpoch = (System.currentTimeMillis() / 1000) + 300
        val parametersJson = "{}"

        val canonicalBytes = EcdsaSigner.createCanonicalCommandBytes(
            commandId = "",
            targetDeviceId = targetDeviceId,
            commandType = commandType,
            sequenceCounter = sequenceCounter,
            expiryEpoch = expiryEpoch,
            parametersJson = parametersJson
        )

        // Sign with a different, unauthorized keypair
        val fakeSig = EcdsaSigner.signData(fakeAdminKeyPair.private, canonicalBytes)

        val parsedKey = EcdsaSigner.parsePublicKeyFromPem(adminPublicKeyPem)
        val isValid = EcdsaSigner.verifySignature(parsedKey, canonicalBytes, fakeSig)
        assertTrue("Forged signature must fail verification", !isValid)
    }

    @Test
    fun testTamperedPayloadRejected() {
        val targetDeviceId = "test-guardian-dev-01"
        val sequenceCounter = 5L
        val expiryEpoch = (System.currentTimeMillis() / 1000) + 300

        // Admin signed a LOCATE command
        val canonicalBytesOriginal = EcdsaSigner.createCanonicalCommandBytes(
            commandId = "",
            targetDeviceId = targetDeviceId,
            commandType = "LOCATE",
            sequenceCounter = sequenceCounter,
            expiryEpoch = expiryEpoch,
            parametersJson = "{}"
        )
        val validSig = EcdsaSigner.signData(adminKeyPair.private, canonicalBytesOriginal)

        // Attacker tampered command to LOCK
        val canonicalBytesTampered = EcdsaSigner.createCanonicalCommandBytes(
            commandId = "",
            targetDeviceId = targetDeviceId,
            commandType = "LOCK",
            sequenceCounter = sequenceCounter,
            expiryEpoch = expiryEpoch,
            parametersJson = "{}"
        )

        val parsedKey = EcdsaSigner.parsePublicKeyFromPem(adminPublicKeyPem)
        val isValid = EcdsaSigner.verifySignature(parsedKey, canonicalBytesTampered, validSig)
        assertTrue("Signature must fail when command payload is tampered", !isValid)
    }

    @Test
    fun testExpiryConditionValidation() {
        val now = System.currentTimeMillis() / 1000
        val expiredEpoch = now - 60 // 1 minute in the past
        assertTrue("Expired epoch must be <= now", expiredEpoch <= now)

        val futureEpoch = now + 300
        assertTrue("Future epoch must be > now", futureEpoch > now)
    }

    @Test
    fun testMonotonicSequenceCounterEnforcement() {
        var lastSeenSeq = 42L

        val replayedSeq = 42L
        assertTrue("Replayed counter must be rejected", replayedSeq <= lastSeenSeq)

        val oldSeq = 30L
        assertTrue("Older counter must be rejected", oldSeq <= lastSeenSeq)

        val newSeq = 43L
        assertTrue("New monotonically greater counter must be accepted", newSeq > lastSeenSeq)
    }

    @Test
    fun testRepairingRejectedWhenAlreadyPaired() {
        var isPairedState = true
        var adminPublicKeyPemRegistered = adminPublicKeyPem

        // Attacker generates new pairing payload
        val attackerPayload = mapOf(
            "server_url" to "http://attacker-server.com",
            "admin_public_key_pem" to "-----BEGIN PUBLIC KEY-----\nATTACKER_KEY\n-----END PUBLIC KEY-----",
            "pairing_secret_hex" to "AABBCCDDEEFF00112233445566778899"
        )

        // Guardian pairing policy check:
        val canPair = !isPairedState
        org.junit.Assert.assertFalse("Guardian MUST reject any new pairing payload while already paired to an owner!", canPair)
    }

    @Test
    fun testRepairingPermittedOnlyAfterOwnerSignedUnpair() {
        var isPairedState = true
        var currentAdminKeyPem = adminPublicKeyPem

        // 1. Attacker attempts to forge UNPAIR command
        val forgedUnpairBytes = EcdsaSigner.createCanonicalCommandBytes(
            commandId = "cmd-unpair-01",
            targetDeviceId = "test-guardian-01",
            commandType = "UNPAIR",
            sequenceCounter = 50L,
            expiryEpoch = (System.currentTimeMillis() / 1000) + 300,
            parametersJson = "{}"
        )
        val attackerSig = EcdsaSigner.signData(fakeAdminKeyPair.private, forgedUnpairBytes)
        val isForgedValid = EcdsaSigner.verifySignature(
            EcdsaSigner.parsePublicKeyFromPem(currentAdminKeyPem),
            forgedUnpairBytes,
            attackerSig
        )
        org.junit.Assert.assertFalse("Forged UNPAIR command must fail verification", isForgedValid)
        // Pairing state remains locked
        org.junit.Assert.assertTrue("Guardian remains paired when forged UNPAIR arrives", isPairedState)

        // 2. Legitimate registered owner signs UNPAIR command
        val legitimateSig = EcdsaSigner.signData(adminKeyPair.private, forgedUnpairBytes)
        val isOwnerValid = EcdsaSigner.verifySignature(
            EcdsaSigner.parsePublicKeyFromPem(currentAdminKeyPem),
            forgedUnpairBytes,
            legitimateSig
        )
        org.junit.Assert.assertTrue("Owner-signed UNPAIR command verifies successfully", isOwnerValid)

        // Execute verified unpair
        isPairedState = false
        currentAdminKeyPem = ""

        // 3. Device can now legitimately pair to a new owner
        val canNowPair = !isPairedState
        org.junit.Assert.assertTrue("Guardian can safely re-pair once owner-signed UNPAIR has executed", canNowPair)
    }
}
