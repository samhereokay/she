package com.antitheft.common

import com.antitheft.common.crypto.CryptoConstants
import com.antitheft.common.crypto.EcdsaSigner
import com.antitheft.common.crypto.HmacManager
import com.antitheft.common.protocol.SmsPduSerializer
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class CommonCoreTest {

    private val testSecretKey = "super_secure_shared_pairing_secret_32bytes!!".toByteArray(Charsets.UTF_8)

    @Test
    fun testHmac48BitLengthAndVerification() {
        val payload = "TEST_PAYLOAD_FOR_HMAC".toByteArray(Charsets.UTF_8)
        val hmacHex = HmacManager.compute48BitHmac(testSecretKey, payload)

        // Must be exactly 12 hex characters = 6 bytes = 48 bits
        assertEquals(12, hmacHex.length)
        assertEquals(CryptoConstants.HMAC_HEX_LENGTH, hmacHex.length)

        // Valid verification
        assertTrue(HmacManager.verify48BitHmac(testSecretKey, payload, hmacHex))

        // Tampered payload fails
        val tamperedPayload = "TEST_PAYLOAD_FOR_HMAC_MUTATED".toByteArray(Charsets.UTF_8)
        assertFalse(HmacManager.verify48BitHmac(testSecretKey, tamperedPayload, hmacHex))

        // Wrong key fails
        val wrongKey = "wrong_secret_key_32bytes_value!!".toByteArray(Charsets.UTF_8)
        assertFalse(HmacManager.verify48BitHmac(wrongKey, payload, hmacHex))
    }

    @Test
    fun testEcdsaP256CanonicalBytesAndSignVerify() {
        // Generate EC P-256 KeyPair
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val deviceId = "device-uuid-12345"
        val sequenceNumber = 42L
        val captureEpoch = 1729000000L
        val latitude = 37.774929
        val longitude = -122.419416
        val accuracy = 12.5f

        val canonicalBytes = EcdsaSigner.createCanonicalBytes(
            deviceId, sequenceNumber, captureEpoch, latitude, longitude, accuracy
        )

        val signature = EcdsaSigner.signData(keyPair.private, canonicalBytes)
        assertTrue(signature.isNotEmpty())

        // Verify valid
        val isValid = EcdsaSigner.verifySignature(keyPair.public, canonicalBytes, signature)
        assertTrue(isValid)

        // Tampered data must fail
        val tamperedBytes = EcdsaSigner.createCanonicalBytes(
            deviceId, sequenceNumber + 1, captureEpoch, latitude, longitude, accuracy
        )
        val isTamperedValid = EcdsaSigner.verifySignature(keyPair.public, tamperedBytes, signature)
        assertFalse(isTamperedValid)
    }

    @Test
    fun testSmsPduSerializerFormattingAndParsing() {
        val alias = "Pixel8"
        val lat = 37.77492
        val lng = -122.41941
        val acc = 14
        val epoch = 1729036800L
        val seq = 104L

        val pdu = SmsPduSerializer.formatLocationReport(
            alias, lat, lng, acc, epoch, seq, testSecretKey
        )

        // Assert strictly under single-segment GSM 7-bit SMS limit (160 chars)
        assertTrue(pdu.length <= 160)
        // Typical length is ~55-65 characters
        assertTrue(pdu.length in 45..75)
        assertTrue(pdu.startsWith("ST:Pixel8|"))

        // Parse and verify HMAC
        val parsed = SmsPduSerializer.parseLocationReport(pdu, testSecretKey)
        assertNotNull(parsed)
        parsed?.let {
            assertEquals("Pixel8", it.alias)
            assertEquals(lat, it.latitude, 0.00001)
            assertEquals(lng, it.longitude, 0.00001)
            assertEquals(acc, it.accuracyMeters)
            assertEquals(epoch, it.captureEpochSec)
            assertEquals(seq, it.sequenceNumber)
            assertEquals(12, it.hmacHex.length)
            assertTrue(it.isHmacValid)
        }

        // Tampered PDU fails HMAC check
        val tamperedPdu = pdu.replace("104", "105")
        val parsedTampered = SmsPduSerializer.parseLocationReport(tamperedPdu, testSecretKey)
        assertNotNull(parsedTampered)
        assertFalse(parsedTampered!!.isHmacValid)
    }

    @Test
    fun testInboundCommandFormattingAndParsing() {
        val command = "LOCATE"
        val seq = 201L
        val expiry = 1729037400L

        val cmdPdu = SmsPduSerializer.formatInboundCommand(command, seq, expiry, testSecretKey)
        assertTrue(cmdPdu.startsWith("CMD:LOCATE|"))
        assertTrue(cmdPdu.length <= 160)

        val parsed = SmsPduSerializer.parseInboundCommand(cmdPdu, testSecretKey)
        assertNotNull(parsed)
        parsed?.let {
            assertEquals("LOCATE", it.commandType)
            assertEquals(seq, it.sequenceNumber)
            assertEquals(expiry, it.expiryEpochSec)
            assertTrue(it.isHmacValid)
        }
    }
}
