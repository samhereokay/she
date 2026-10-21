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

    @Test
    fun testPairingSecretRotationOldSecretFailsHmac() {
        val nowEpoch = 1770000000L
        val expiryEpoch = nowEpoch + 300L

        // Phase 1: Device is paired with Secret A
        val secretA = testSharedSecret
        val cmdWithSecretA = SmsPduSerializer.formatInboundCommand(
            commandType = "LOCATE",
            sequenceNumber = 1L,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = secretA
        )

        val result1 = SmsCommandProcessor.processInboundPdu(
            body = cmdWithSecretA,
            storage = mockStorage,
            nowEpochSec = nowEpoch
        )
        assertEquals(SmsProcessStatus.ACCEPTED, result1.status)
        assertEquals(1L, mockStorage.getLastInboundSmsSequence())

        // Phase 2: Owner executes UNPAIR, re-pairs with brand-new rotated Secret B
        val secretB = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val rotatedStorage = MockInboundSmsStorage(secretB, currentInboundSeq = 0L)

        // Attacker attempts to send command signed with old Secret A
        val staleCmdWithSecretA = SmsPduSerializer.formatInboundCommand(
            commandType = "LOCK",
            sequenceNumber = 2L,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = secretA // Old rotated secret!
        )

        val staleResult = SmsCommandProcessor.processInboundPdu(
            body = staleCmdWithSecretA,
            storage = rotatedStorage,
            nowEpochSec = nowEpoch
        )
        assertEquals(SmsProcessStatus.DROPPED_HMAC_FAILURE, staleResult.status)
        assertTrue(staleResult.reason.contains("failed HMAC"))
        assertEquals(0L, rotatedStorage.getLastInboundSmsSequence())

        // Phase 3: Legitimate Owner sends command signed with new Secret B
        val validCmdWithSecretB = SmsPduSerializer.formatInboundCommand(
            commandType = "LOCK",
            sequenceNumber = 2L,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = secretB
        )

        val validResult = SmsCommandProcessor.processInboundPdu(
            body = validCmdWithSecretB,
            storage = rotatedStorage,
            nowEpochSec = nowEpoch
        )
        assertEquals(SmsProcessStatus.ACCEPTED, validResult.status)
        assertEquals(2L, rotatedStorage.getLastInboundSmsSequence())
    }

    @Test
    fun testClearDeviceOwnerStrictlyProhibitedOverSmsChannel() {
        val nowEpoch = 1770000000L
        val expiryEpoch = nowEpoch + 300L
        val seq = 1L

        val pdu = SmsPduSerializer.formatInboundCommand(
            commandType = "CLEAR_DEVICE_OWNER",
            sequenceNumber = seq,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = testSharedSecret
        )

        val result = SmsCommandProcessor.processInboundPdu(
            body = pdu,
            storage = mockStorage,
            nowEpochSec = nowEpoch
        )

        assertEquals(
            "CLEAR_DEVICE_OWNER must be dropped as channel restricted over SMS",
            SmsProcessStatus.DROPPED_CHANNEL_RESTRICTED,
            result.status
        )
        assertTrue(result.reason.contains("strictly prohibited over SMS channel"))
        // Storage monotonic sequence must NOT advance
        assertEquals(0L, mockStorage.getLastInboundSmsSequence())
    }

    @Test
    fun testMalformedInboundCommandDropped() {
        val malformedBodies = listOf(
            "GARBAGE_PAYLOAD_NOT_SHE",
            "AT1:CMD:",
            "AT1:CMD:INVALID_PARTS",
            "AT1:CMD:LOCATE:NOT_A_NUMBER:1770000300:ABCDEF",
            "AT1:CMD:RING:5:NOT_A_TIMESTAMP:ABCDEF",
            "AT1:UNKNOWN:LOCATE:1:1770000300:ABCDEF"
        )

        for (body in malformedBodies) {
            val result = SmsCommandProcessor.processInboundPdu(
                body = body,
                storage = mockStorage
            )
            assertEquals(
                "Malformed body '$body' must result in DROPPED_MALFORMED",
                SmsProcessStatus.DROPPED_MALFORMED,
                result.status
            )
        }
    }

    @Test
    fun testWrongSenderAddressDropped() {
        mockStorage.trustedPhone = "+15550199"
        val nowEpoch = 1770000000L
        val expiryEpoch = nowEpoch + 300L
        val seq = 1L

        val validPdu = SmsPduSerializer.formatInboundCommand(
            commandType = "LOCATE",
            sequenceNumber = seq,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = testSharedSecret
        )

        // 1. From exact trusted phone -> ACCEPTED
        val trustedResult = SmsCommandProcessor.processInboundPdu(
            body = validPdu,
            storage = mockStorage,
            nowEpochSec = nowEpoch,
            senderAddress = "+15550199"
        )
        assertEquals(SmsProcessStatus.ACCEPTED, trustedResult.status)

        // Advance seq for next test
        val nextSeq = 2L
        val pdu2 = SmsPduSerializer.formatInboundCommand(
            commandType = "LOCATE",
            sequenceNumber = nextSeq,
            expiryEpochSec = expiryEpoch,
            sharedSecretKeyBytes = testSharedSecret
        )

        // 2. From wrong sender phone -> DROPPED_WRONG_SENDER
        val wrongSenderResult = SmsCommandProcessor.processInboundPdu(
            body = pdu2,
            storage = mockStorage,
            nowEpochSec = nowEpoch,
            senderAddress = "+19998887777" // Attacker phone
        )
        assertEquals(SmsProcessStatus.DROPPED_WRONG_SENDER, wrongSenderResult.status)
        assertTrue(wrongSenderResult.reason.contains("does not match registered trusted phone"))
        // Storage counter must NOT advance on wrong sender
        assertEquals(1L, mockStorage.getLastInboundSmsSequence())
    }

    @Test
    fun testInboundSmsContentNeverLoggedOrLeaked() {
        // Privacy invariant test: Verify SmsCommandProcessor and PDU serialization
        // never expose raw SMS body or contents in error reasons, statuses, or exceptions.
        val privatePersonalSms = "Your bank OTP is 849201. Do not share this with anyone."
        val result = SmsCommandProcessor.processInboundPdu(
            body = privatePersonalSms,
            storage = mockStorage
        )
        assertEquals(SmsProcessStatus.DROPPED_MALFORMED, result.status)
        assertFalse(
            "Privacy violation: Inbound SMS content leaked into result reason!",
            result.reason.contains("849201") || result.reason.contains("bank OTP")
        )
        assertNull(result.command)

        val tamperedCommand = "CMD:LOCATE|1|1770000000|deadbeef1234"
        val tamperedResult = SmsCommandProcessor.processInboundPdu(
            body = tamperedCommand,
            storage = mockStorage
        )
        assertEquals(SmsProcessStatus.DROPPED_HMAC_FAILURE, tamperedResult.status)
        assertFalse(
            "Privacy violation: Secret material leaked in failure reason!",
            tamperedResult.reason.contains("deadbeef1234")
        )
    }
}


