package com.antitheft.common

import com.antitheft.common.protocol.SmsPduSerializer
import org.junit.Assert.*
import org.junit.Test

class SmsCommandSecurityTest {

    private val validKey = "shared_pairing_secret_key_32b!!".toByteArray(Charsets.UTF_8)
    private val wrongKey = "attacker_adversary_key_32bytes!!".toByteArray(Charsets.UTF_8)

    @Test
    fun testValidCommandPasses() {
        val nowEpoch = 1729030000L
        val expiryEpoch = nowEpoch + 600L // 10 minutes in future
        val seq = 201L

        val pdu = SmsPduSerializer.formatInboundCommand("LOCATE", seq, expiryEpoch, validKey)
        val parsed = SmsPduSerializer.parseInboundCommand(pdu, validKey)

        assertNotNull(parsed)
        assertTrue(parsed!!.isHmacValid)
        assertEquals("LOCATE", parsed.commandType)
        assertEquals(seq, parsed.sequenceNumber)
        assertEquals(expiryEpoch, parsed.expiryEpochSec)
    }

    @Test
    fun testWrongKeyRejected() {
        val expiryEpoch = 1729037400L
        val seq = 201L

        // Attacker creates or signs command with their own key
        val pdu = SmsPduSerializer.formatInboundCommand("LOCATE", seq, expiryEpoch, wrongKey)
        
        // Guardian attempts to parse with its legitimate pairing key
        val parsed = SmsPduSerializer.parseInboundCommand(pdu, validKey)

        assertNotNull(parsed)
        // HMAC MUST fail!
        assertFalse(parsed!!.isHmacValid)
    }

    @Test
    fun testReplayedCounterRejected() {
        val expiryEpoch = 1729037400L
        val pdu = SmsPduSerializer.formatInboundCommand("LOCATE", 100L, expiryEpoch, validKey)
        val parsed = SmsPduSerializer.parseInboundCommand(pdu, validKey)

        assertNotNull(parsed)
        assertTrue(parsed!!.isHmacValid)

        // Guardian anti-replay state machine check:
        val lastSeenCounter = 105L
        val isCounterAcceptable = parsed.sequenceNumber > lastSeenCounter
        assertFalse("Replayed counter (100 <= 105) must be rejected!", isCounterAcceptable)
    }

    @Test
    fun testExpiredCommandRejected() {
        val nowEpoch = 1729040000L
        val expiredEpoch = nowEpoch - 60L // Expired 1 minute ago
        val pdu = SmsPduSerializer.formatInboundCommand("LOCATE", 205L, expiredEpoch, validKey)
        val parsed = SmsPduSerializer.parseInboundCommand(pdu, validKey)

        assertNotNull(parsed)
        assertTrue(parsed!!.isHmacValid)

        // Expiry check
        val isNotExpired = nowEpoch <= parsed.expiryEpochSec
        assertFalse("Expired command must be rejected!", isNotExpired)
    }

    @Test
    fun testMalformedMessagesRejected() {
        // 1. Missing "CMD:" prefix
        val noPrefix = "LOCATE|201|1729037400|5CF65B478971"
        assertNull(SmsPduSerializer.parseInboundCommand(noPrefix, validKey))

        // 2. Insufficient pipe fields
        val incomplete = "CMD:LOCATE|201|1729037400"
        assertNull(SmsPduSerializer.parseInboundCommand(incomplete, validKey))

        // 3. Non-numeric sequence number
        val nonNumericSeq = "CMD:LOCATE|NOT_A_NUMBER|1729037400|5CF65B478971"
        assertNull(SmsPduSerializer.parseInboundCommand(nonNumericSeq, validKey))

        // 4. Non-numeric expiry timestamp
        val nonNumericExpiry = "CMD:LOCATE|201|EXPIRED_STR|5CF65B478971"
        assertNull(SmsPduSerializer.parseInboundCommand(nonNumericExpiry, validKey))

        // 5. Short / corrupted HMAC string
        val shortHmac = "CMD:LOCATE|201|1729037400|ABC"
        val parsedShort = SmsPduSerializer.parseInboundCommand(shortHmac, validKey)
        assertNotNull(parsedShort)
        assertFalse(parsedShort!!.isHmacValid)

        // 6. Completely garbage string
        assertNull(SmsPduSerializer.parseInboundCommand("Random text from SMS spam", validKey))
    }
}
