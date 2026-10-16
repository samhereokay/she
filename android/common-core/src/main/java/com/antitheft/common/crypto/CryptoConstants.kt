package com.antitheft.common.crypto

object CryptoConstants {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val EC_ALGORITHM = "EC"
    const val EC_CURVE = "secp256r1" // NIST P-256 standard
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    const val HMAC_ALGORITHM = "HmacSHA256"
    
    // Exactly 12 hexadecimal characters = 6 bytes = 48 bits
    const val HMAC_HEX_LENGTH = 12
    const val SMS_MAX_PDU_CHARS = 160
}
