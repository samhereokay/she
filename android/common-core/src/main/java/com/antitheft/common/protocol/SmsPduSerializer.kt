package com.antitheft.common.protocol

import com.antitheft.common.crypto.CryptoConstants
import com.antitheft.common.crypto.HmacManager
import java.util.Locale

/**
 * Data class representing a parsed SMS location report from Guardian.
 */
data class SmsLocationReport(
    val alias: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int,
    val captureEpochSec: Long,
    val sequenceNumber: Long,
    val hmacHex: String,
    val isHmacValid: Boolean = false
)

/**
 * Data class representing an inbound SMS command sent to Guardian.
 */
data class SmsInboundCommand(
    val commandType: String,
    val sequenceNumber: Long,
    val expiryEpochSec: Long,
    val hmacHex: String,
    val isHmacValid: Boolean = false
)

/**
 * Serializes and parses compact 7-bit ASCII PDUs for the SMS emergency channel.
 */
object SmsPduSerializer {

    private const val PREFIX_STATUS = "ST:"
    private const val PREFIX_COMMAND = "CMD:"

    /**
     * Formats location report into compact 7-bit ASCII PDU.
     * Guaranteed < 160 characters (typically ~58-65 characters).
     */
    fun formatLocationReport(
        alias: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Int,
        captureEpochSec: Long,
        sequenceNumber: Long,
        sharedSecretKeyBytes: ByteArray
    ): String {
        // Strip non-ASCII or pipe characters from alias
        val cleanAlias = alias.replace("|", "").replace(":", "").filter { it.code in 32..126 }
        
        // Body without HMAC
        val bodyWithoutHmac = String.format(
            Locale.US,
            "%s%s|%.5f,%.5f|%d|%d|%d",
            PREFIX_STATUS,
            cleanAlias,
            latitude,
            longitude,
            accuracyMeters,
            captureEpochSec,
            sequenceNumber
        )
        
        // Calculate 48-bit HMAC (12 hex chars) over the body
        val hmacHex = HmacManager.compute48BitHmac(
            sharedSecretKeyBytes,
            bodyWithoutHmac.toByteArray(Charsets.US_ASCII)
        )
        
        val fullPdu = "$bodyWithoutHmac|$hmacHex"
        
        // Strict invariant check: must fit in single-part GSM 7-bit SMS
        check(fullPdu.length <= CryptoConstants.SMS_MAX_PDU_CHARS) {
            "PDU length ${fullPdu.length} exceeds 160 character limit!"
        }
        
        return fullPdu
    }

    /**
     * Parses an incoming location report SMS and validates the 48-bit HMAC.
     */
    fun parseLocationReport(pduString: String, sharedSecretKeyBytes: ByteArray? = null): SmsLocationReport? {
        val trimmed = pduString.trim()
        if (!trimmed.startsWith(PREFIX_STATUS)) return null
        
        val content = trimmed.substring(PREFIX_STATUS.length)
        val parts = content.split("|")
        if (parts.size != 6) return null
        
        return try {
            val alias = parts[0]
            val coords = parts[1].split(",")
            if (coords.size != 2) return null
            val lat = coords[0].toDouble()
            val lng = coords[1].toDouble()
            val acc = parts[2].toInt()
            val epoch = parts[3].toLong()
            val seq = parts[4].toLong()
            val hmac = parts[5]
            
            var isValid = false
            if (sharedSecretKeyBytes != null) {
                val reconstructedBody = "$PREFIX_STATUS$alias|${parts[1]}|$acc|$epoch|$seq"
                isValid = HmacManager.verify48BitHmac(
                    sharedSecretKeyBytes,
                    reconstructedBody.toByteArray(Charsets.US_ASCII),
                    hmac
                )
            }
            
            SmsLocationReport(
                alias = alias,
                latitude = lat,
                longitude = lng,
                accuracyMeters = acc,
                captureEpochSec = epoch,
                sequenceNumber = seq,
                hmacHex = hmac,
                isHmacValid = isValid
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Formats inbound command into compact ASCII PDU.
     * CMD:<type>|<seq>|<expiry>|<hmac12>
     */
    fun formatInboundCommand(
        commandType: String,
        sequenceNumber: Long,
        expiryEpochSec: Long,
        sharedSecretKeyBytes: ByteArray
    ): String {
        val cleanCmd = commandType.uppercase(Locale.US).filter { it.code in 32..126 }
        val bodyWithoutHmac = "$PREFIX_COMMAND$cleanCmd|$sequenceNumber|$expiryEpochSec"
        val hmacHex = HmacManager.compute48BitHmac(
            sharedSecretKeyBytes,
            bodyWithoutHmac.toByteArray(Charsets.US_ASCII)
        )
        return "$bodyWithoutHmac|$hmacHex"
    }

    /**
     * Parses an inbound SMS command and verifies HMAC.
     */
    fun parseInboundCommand(pduString: String, sharedSecretKeyBytes: ByteArray): SmsInboundCommand? {
        val trimmed = pduString.trim()
        if (!trimmed.startsWith(PREFIX_COMMAND)) return null
        
        val content = trimmed.substring(PREFIX_COMMAND.length)
        val parts = content.split("|")
        if (parts.size != 4) return null
        
        return try {
            val cmdType = parts[0]
            val seq = parts[1].toLong()
            val expiry = parts[2].toLong()
            val hmac = parts[3]
            
            val reconstructedBody = "$PREFIX_COMMAND$cmdType|$seq|$expiry"
            val isValid = HmacManager.verify48BitHmac(
                sharedSecretKeyBytes,
                reconstructedBody.toByteArray(Charsets.US_ASCII),
                hmac
            )
            
            SmsInboundCommand(
                commandType = cmdType,
                sequenceNumber = seq,
                expiryEpochSec = expiry,
                hmacHex = hmac,
                isHmacValid = isValid
            )
        } catch (e: Exception) {
            null
        }
    }
}
