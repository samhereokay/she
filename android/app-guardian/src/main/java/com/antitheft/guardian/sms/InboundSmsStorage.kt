package com.antitheft.guardian.sms

/**
 * Storage interface required by SmsCommandProcessor.
 * Decouples SMS security and anti-replay verification from Android framework APIs.
 */
interface InboundSmsStorage {
    fun getPairingSecret(): ByteArray
    fun getLastInboundSmsSequence(): Long
    fun setLastInboundSmsSequence(seq: Long)
    fun getTrustedPhoneNumber(): String
    fun getDeviceAlias(): String
    fun getNextSequenceNumber(): Long
}
