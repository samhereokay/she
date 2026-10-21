package com.antitheft.guardian.sms

import com.antitheft.common.protocol.SmsInboundCommand
import com.antitheft.common.protocol.SmsPduSerializer

enum class SmsProcessStatus {
    ACCEPTED,
    DROPPED_HMAC_FAILURE,
    DROPPED_REPLAY,
    DROPPED_EXPIRED,
    DROPPED_MALFORMED,
    DROPPED_CHANNEL_RESTRICTED,
    DROPPED_WRONG_SENDER
}

data class SmsProcessResult(
    val status: SmsProcessStatus,
    val command: SmsInboundCommand? = null,
    val reason: String = ""
)

/**
 * Pure business logic processor for inbound AT1 SMS commands.
 * Enforces cryptographic HMAC verification, monotonic inbound anti-replay sequence checks,
 * sender address validation, and expiration window validation against InboundSmsStorage.
 */
object SmsCommandProcessor {

    fun processInboundPdu(
        body: String,
        storage: InboundSmsStorage,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        senderAddress: String? = null
    ): SmsProcessResult {
        // 0. Sender validation if senderAddress provided
        val trustedPhone = storage.getTrustedPhoneNumber()
        if (!senderAddress.isNullOrBlank() && trustedPhone.isNotBlank()) {
            val normSender = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeToE164(senderAddress)
            val normTrusted = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeToE164(trustedPhone)
            if (normSender.isNotBlank() && normTrusted.isNotBlank() && normSender != normTrusted) {
                return SmsProcessResult(
                    status = SmsProcessStatus.DROPPED_WRONG_SENDER,
                    reason = "Sender address '$senderAddress' does not match registered trusted phone '$trustedPhone'"
                )
            }
        }

        val sharedSecret = storage.getPairingSecret()
        val command = SmsPduSerializer.parseInboundCommand(body, sharedSecret)
            ?: return SmsProcessResult(
                status = SmsProcessStatus.DROPPED_MALFORMED,
                reason = "Payload could not be parsed as valid AT1 PDU"
            )

        // 1. Verify cryptographic HMAC
        if (!command.isHmacValid) {
            return SmsProcessResult(
                status = SmsProcessStatus.DROPPED_HMAC_FAILURE,
                command = command,
                reason = "Inbound SMS failed HMAC verification (wrong key or tampered bytes)"
            )
        }

        // 2. Monotonic anti-replay check
        val lastSeq = storage.getLastInboundSmsSequence()
        if (command.sequenceNumber <= lastSeq) {
            return SmsProcessResult(
                status = SmsProcessStatus.DROPPED_REPLAY,
                command = command,
                reason = "Inbound SMS sequence ${command.sequenceNumber} <= last seen $lastSeq (replayed or stale)"
            )
        }

        // 3. Expiration window check
        if (nowEpochSec > command.expiryEpochSec) {
            return SmsProcessResult(
                status = SmsProcessStatus.DROPPED_EXPIRED,
                command = command,
                reason = "Inbound SMS expired at ${command.expiryEpochSec}, current epoch is $nowEpochSec"
            )
        }

        // 4. Channel Restriction: CLEAR_DEVICE_OWNER is strictly prohibited over SMS
        if (command.commandType == "CLEAR_DEVICE_OWNER") {
            return SmsProcessResult(
                status = SmsProcessStatus.DROPPED_CHANNEL_RESTRICTED,
                command = command,
                reason = "SECURITY VIOLATION: CLEAR_DEVICE_OWNER is strictly prohibited over SMS channel. Signed Admin online command with fresh TOTP required."
            )
        }

        // Persist verified monotonic counter
        storage.setLastInboundSmsSequence(command.sequenceNumber)

        return SmsProcessResult(
            status = SmsProcessStatus.ACCEPTED,
            command = command,
            reason = "Authenticated and verified monotonic command"
        )
    }
}
