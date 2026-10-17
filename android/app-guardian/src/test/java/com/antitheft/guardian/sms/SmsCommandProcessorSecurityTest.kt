package com.antitheft.guardian.sms

import com.antitheft.common.protocol.SmsPduSerializer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

class SmsCommandProcessorSecurityTest {

    private lateinit var mockStorage: MockInboundSmsStorage
    private val testSharedSecret = ByteArray(32).apply { SecureRandom().nextBytes(this) }

    class MockInboundSmsStorage(
        private val secret: ByteArray,
        var currentInboundSeq: Long = 0L,
        var trustedPhone: String = "+15550199",
        var alias: String = "TestGuardian",
        var outSeq: Long = 0L
    ) : InboundSmsStorage {
        override fun getPairingSecret(): ByteArray = secret
        override fun getLastInboundSmsSequence(): Long = currentInboundSeq
        override fun setLastInboundSmsSequence(seq: Long) {
            currentInboundSeq = seq
        }
        override fun getTrustedPhoneNumber(): String = trustedPhone
        override fun getDeviceAlias(): String = alias
        override fun getNextSequenceNumber(): Long {
            outSeq += 1
            return outSeq
        }
    }

    @Before
    fun setup() {
        mockStorage = MockInboundSmsStorage(testSharedSecret, currentInboundSeq = 0L)
    }

    @Test
    fun testFirstValidInboundSmsAcceptedAndAdvancesSequence() {
        val nowEpoch = 1770000000L
        val expiryEpoch = nowEpoch + 300L
        val seq = 5L

        val pdu = SmsPduSerializer.formatInboundCommand(
            commandType = "LOCATE",
            sequenceNumber = seq,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = testSharedSecret
        )

        val result = SmsCommandProcessor.processInboundPdu(
            body = pdu,
            storage = mockStorage,
            nowEpochSec = nowEpoch
        )

        assertEquals(SmsProcessStatus.ACCEPTED, result.status)
        assertNotNull(result.command)
        assertEquals("LOCATE", result.command?.commandType)
        assertEquals(5L, result.command?.sequenceNumber)
        // Verify persistent counter advanced in storage
        assertEquals(5L, mockStorage.getLastInboundSmsSequence())
    }

    @Test
    fun testReplayedSequenceDroppedSilently() {
        val nowEpoch = 1770000000L
        val expiryEpoch = nowEpoch + 300L
        val seq = 5L

        val pdu = SmsPduSerializer.formatInboundCommand(
            commandType = "LOCK",
            sequenceNumber = seq,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = testSharedSecret
        )

        // 1. First presentation accepted
        val firstResult = SmsCommandProcessor.processInboundPdu(
            body = pdu,
            storage = mockStorage,
            nowEpochSec = nowEpoch
        )
        assertEquals(SmsProcessStatus.ACCEPTED, firstResult.status)
        assertEquals(5L, mockStorage.getLastInboundSmsSequence())

        // 2. Replay of the exact same PDU
        val replayResult = SmsCommandProcessor.processInboundPdu(
            body = pdu,
            storage = mockStorage,
            nowEpochSec = nowEpoch + 10L
        )
        assertEquals(SmsProcessStatus.DROPPED_REPLAY, replayResult.status)
        // Counter must not be corrupted
        assertEquals(5L, mockStorage.getLastInboundSmsSequence())
    }

    @Test
    fun testStaleLowerSequenceDropped() {
        // Set storage sequence to 10
        mockStorage.setLastInboundSmsSequence(10L)

        val nowEpoch = 1770000000L
        val expiryEpoch = nowEpoch + 300L

        // Attacker presents sequence 8 (stale)
        val pdu = SmsPduSerializer.formatInboundCommand(
            commandType = "RING",
            sequenceNumber = 8L,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = testSharedSecret
        )

        val result = SmsCommandProcessor.processInboundPdu(
            body = pdu,
            storage = mockStorage,
            nowEpochSec = nowEpoch
        )
        assertEquals(SmsProcessStatus.DROPPED_REPLAY, result.status)
        assertEquals(10L, mockStorage.getLastInboundSmsSequence())
    }

    @Test
    fun testForgedHmacDropped() {
        val nowEpoch = 1770000000L
        val expiryEpoch = nowEpoch + 300L
        val seq = 15L

        val wrongSecret = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val forgedPdu = SmsPduSerializer.formatInboundCommand(
            commandType = "STOLEN_MODE",
            sequenceNumber = seq,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = wrongSecret // Forged with wrong key
        )

        val result = SmsCommandProcessor.processInboundPdu(
            body = forgedPdu,
            storage = mockStorage,
            nowEpochSec = nowEpoch
        )
        assertEquals(SmsProcessStatus.DROPPED_HMAC_FAILURE, result.status)
        assertEquals(0L, mockStorage.getLastInboundSmsSequence())
    }

    @Test
    fun testExpiredCommandDropped() {
        val nowEpoch = 1770000000L
        val expiredEpoch = nowEpoch - 60L // Expired 1 minute ago
        val seq = 20L

        val pdu = SmsPduSerializer.formatInboundCommand(
            commandType = "LOCATE",
            sequenceNumber = seq,
            expiryEpochSec = expiredEpoch,
            sharedSecretKeyBytes = testSharedSecret
        )

        val result = SmsCommandProcessor.processInboundPdu(
            body = pdu,
            storage = mockStorage,
            nowEpochSec = nowEpoch
        )
        assertEquals(SmsProcessStatus.DROPPED_EXPIRED, result.status)
        assertEquals(0L, mockStorage.getLastInboundSmsSequence())
    }

    @Test
    fun testMonotonicallyIncreasingSequenceChainAccepted() {
        val nowEpoch = 1770000000L
        val expiryEpoch = nowEpoch + 600L

        val commands = listOf(
            Pair("LOCATE", 100L),
            Pair("RING", 101L),
            Pair("LOCK", 105L),
            Pair("STOLEN_MODE", 110L),
            Pair("EXIT_KIOSK", 112L)
        )

        for ((cmdType, seq) in commands) {
            val pdu = SmsPduSerializer.formatInboundCommand(
                commandType = cmdType,
                sequenceNumber = seq,
                expiryEpochSec = expiryEpoch,
                sharedSecretKeyBytes = testSharedSecret
            )
            val result = SmsCommandProcessor.processInboundPdu(
                body = pdu,
                storage = mockStorage,
                nowEpochSec = nowEpoch
            )
            assertEquals(SmsProcessStatus.ACCEPTED, result.status)
            assertEquals(seq, mockStorage.getLastInboundSmsSequence())
        }
    }
}
