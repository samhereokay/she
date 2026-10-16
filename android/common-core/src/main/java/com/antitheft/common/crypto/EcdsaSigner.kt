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
     * Constructs canonical byte representation for Admin command signing.
     * Matches backend specification: <pairing_id>|<command_id>|<target_device_id>|<command_type>|<sequence>|<expiry_epoch>|<sha256(parameters)>
     */
    fun createCanonicalCommandBytes(
        pairingId: String = "",
        commandId: String = "",
        targetDeviceId: String,
        commandType: String,
        sequenceCounter: Long,
        expiryEpoch: Long,
        parametersJson: String = "{}"
    ): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val paramsHash = md.digest(parametersJson.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val canonicalStr = "${pairingId.trim()}|${commandId.trim()}|${targetDeviceId.trim()}|${commandType.trim().uppercase()}|$sequenceCounter|$expiryEpoch|$paramsHash"
        return canonicalStr.toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses standard EC X.509 PEM formatted public key.
     */
    fun parsePublicKeyFromPem(pem: String): PublicKey {
        val cleanPem = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        val keyBytes = java.util.Base64.getDecoder().decode(cleanPem)
        val spec = java.security.spec.X509EncodedKeySpec(keyBytes)
        val kf = java.security.KeyFactory.getInstance("EC")
        return kf.generatePublic(spec)
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
