package com.antitheft.common.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Locale

/**
 * Handles ECDSA P-256 (SHA256withECDSA) signing and verification.
 */
object EcdsaSigner {

    /**
     * Constructs canonical byte representation for location fix signature.
     * Matches backend specification: <device_id>|<sequence>|<epoch_sec>|<lat:.6f>|<lng:.6f>|<acc:.1f>
     */
    fun createCanonicalBytes(
        deviceId: String,
        sequenceNumber: Long,
        captureEpochSec: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Float
    ): ByteArray {
        val canonicalStr = String.format(
            Locale.US,
            "%s|%d|%d|%.6f|%.6f|%.1f",
            deviceId,
            sequenceNumber,
            captureEpochSec,
            latitude,
            longitude,
            accuracy
        )
        return canonicalStr.toByteArray(Charsets.UTF_8)
    }

    /**
     * Signs data using private key (hardware Keystore or software EC key).
     * Output is standard ASN.1 DER format.
     */
    fun signData(privateKey: PrivateKey, dataBytes: ByteArray): ByteArray {
        val signature = Signature.getInstance(CryptoConstants.SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(dataBytes)
        return signature.sign()
    }

    /**
     * Verifies ASN.1 DER ECDSA signature against public key.
     */
    fun verifySignature(publicKey: PublicKey, dataBytes: ByteArray, derSignatureBytes: ByteArray): Boolean {
        return try {
            val signature = Signature.getInstance(CryptoConstants.SIGNATURE_ALGORITHM)
            signature.initVerify(publicKey)
            signature.update(dataBytes)
            signature.verify(derSignatureBytes)
        } catch (e: Exception) {
            false
        }
    }
}
