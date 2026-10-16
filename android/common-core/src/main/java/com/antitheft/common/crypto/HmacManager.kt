package com.antitheft.common.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Locale

/**
 * Manages 48-bit truncated HMAC-SHA256 calculations for SMS message integrity.
 */
object HmacManager {

    /**
     * Compute 48-bit truncated HMAC-SHA256 (exactly 12 hex characters).
     */
    fun compute48BitHmac(keyBytes: ByteArray, dataBytes: ByteArray): String {
        val mac = Mac.getInstance(CryptoConstants.HMAC_ALGORITHM)
        val secretKey = SecretKeySpec(keyBytes, CryptoConstants.HMAC_ALGORITHM)
        mac.init(secretKey)
        val fullDigest = mac.doFinal(dataBytes)
        
        // Convert first 6 bytes (48 bits) to 12 hex characters
        val hexStringBuilder = StringBuilder(CryptoConstants.HMAC_HEX_LENGTH)
        for (i in 0 until (CryptoConstants.HMAC_HEX_LENGTH / 2)) {
            hexStringBuilder.append(String.format(Locale.US, "%02X", fullDigest[i]))
        }
        return hexStringBuilder.toString()
    }

    /**
     * Constant-time verification of received 48-bit truncated HMAC hex string.
     */
    fun verify48BitHmac(keyBytes: ByteArray, dataBytes: ByteArray, receivedHmacHex: String): Boolean {
        if (receivedHmacHex.length != CryptoConstants.HMAC_HEX_LENGTH) {
            return false
        }
        val expectedHex = compute48BitHmac(keyBytes, dataBytes)
        // Constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
            expectedHex.uppercase(Locale.US).toByteArray(Charsets.US_ASCII),
            receivedHmacHex.uppercase(Locale.US).toByteArray(Charsets.US_ASCII)
        )
    }
}
