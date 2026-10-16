package com.antitheft.common

import com.antitheft.common.crypto.CryptoConstants
import com.antitheft.common.crypto.EcdsaSigner
import com.antitheft.common.crypto.HmacManager
import com.antitheft.common.protocol.SmsPduSerializer
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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

        assertTrue(pdu.length <= 160)
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
    fun testSharedCrossPlatformCryptoVectors() {
        // 1. Shared 48-bit HMAC Vector (must match Python test_shared_crypto_test_vectors)
        val hmacKey = "shared_pairing_secret_key_32b!!".toByteArray(Charsets.UTF_8)
        val hmacMsg = "ST:Pixel8|37.77492,-122.41941|14|1729036800|104".toByteArray(Charsets.UTF_8)
        val expectedHmac12 = "D7D35CACE300"

        val computedHmac = HmacManager.compute48BitHmac(hmacKey, hmacMsg)
        assertEquals("Kotlin HMAC must match Python byte-for-byte!", expectedHmac12, computedHmac)
        assertTrue(HmacManager.verify48BitHmac(hmacKey, hmacMsg, expectedHmac12))

        // 2. Shared Inbound Command Vector
        val cmdMsg = "CMD:LOCATE|201|1729037400".toByteArray(Charsets.UTF_8)
        val expectedCmdHmac12 = "5CF65B478971"
        val computedCmdHmac = HmacManager.compute48BitHmac(hmacKey, cmdMsg)
        assertEquals("Kotlin Inbound HMAC must match Python!", expectedCmdHmac12, computedCmdHmac)

        // 3. Shared ECDSA P-256 Vector
        val pubPem = "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEU3xUsenCsDkOCGY95Bgy00tLbbUj\ns0XylDYxWcXPe+L4Cm9/Hp2n4gUWEQvHONq29kTUKzHzAS0tGh8VWWow/w==\n-----END PUBLIC KEY-----"
        val cleanPubB64 = pubPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .trim()
        val pubBytes = Base64.getDecoder().decode(cleanPubB64)
        val keySpec = X509EncodedKeySpec(pubBytes)
        val kf = KeyFactory.getInstance("EC")
        val pubKey = kf.generatePublic(keySpec)

        val canonicalData = "device-abc-123|100|1729000000|37.774929|-122.419416|12.5".toByteArray(Charsets.UTF_8)
        val sigDerB64 = "MEQCIC39XomullhKQCiTlpVKbSaCXW/PXfH4/dsFBRGiv17yAiBqUTjvQdgs/OmCEb54epg5QiUK3YEiFgtm/iTlN5Wf1Q=="
        val sigDerBytes = Base64.getDecoder().decode(sigDerB64)

        assertTrue("Kotlin must verify the Python-generated ECDSA signature!", EcdsaSigner.verifySignature(pubKey, canonicalData, sigDerBytes))
    }
}
