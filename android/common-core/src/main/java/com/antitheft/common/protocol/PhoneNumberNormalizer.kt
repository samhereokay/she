package com.antitheft.common.protocol

import android.os.Build

/**
 * Standardized phone number normalizer conforming to ITU-T E.164 international format.
 * Provides transparent compatibility between real physical carrier networks (requiring full
 * international +E.164 numbers) and Android QEMU emulator radio routing.
 */
object PhoneNumberNormalizer {

    private val E164_REGEX = Regex("""^\+[1-9]\d{6,14}$""")
    private val EMULATOR_QEMU_REGEX = Regex("""^(?:\+?1555521)?(\d{4})$""")

    /**
     * Normalizes a phone number to standard E.164 international format (+CountryCodeDigits).
     * e.g., "+1 (555) 521-5556" -> "+15555215556"
     */
    fun normalizeToE164(rawNumber: String): String {
        val clean = rawNumber.trim().replace(Regex("""[\s\-\(\)\.]"""), "")
        if (clean.isBlank()) return ""

        return if (clean.startsWith("+")) {
            val digits = clean.substring(1).filter { it.isDigit() }
            "+$digits"
        } else {
            val digits = clean.filter { it.isDigit() }
            if (digits.length == 4) {
                // Shorthand emulator console port e.g. "5556" -> full international format "+15555215556"
                "+1555521$digits"
            } else {
                "+$digits"
            }
        }
    }

    /**
     * Determines whether a phone number is a valid international E.164 or emulator test address.
     */
    fun isValid(rawNumber: String): Boolean {
        val normalized = normalizeToE164(rawNumber)
        return E164_REGEX.matches(normalized)
    }

    /**
     * Prepares destination phone number for SMS dispatch.
     * On physical devices: returns full international E.164 number.
     * On Android QEMU emulators: routes to peer emulator console port (e.g. "5556")
     * so QEMU's internal simulated modem delivers the SMS to the peer emulator.
     */
    fun normalizeForSmsDispatch(rawNumber: String): String {
        val normalized = normalizeToE164(rawNumber)
        if (normalized.isBlank()) return ""

        if (isAndroidEmulator()) {
            val match = EMULATOR_QEMU_REGEX.find(normalized)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return normalized
    }

    /**
     * Detects if running inside the Android SDK emulator.
     */
    fun isAndroidEmulator(): Boolean {
        return try {
            val fp = Build.FINGERPRINT ?: ""
            val model = Build.MODEL ?: ""
            val hw = Build.HARDWARE ?: ""
            val product = Build.PRODUCT ?: ""
            (fp.startsWith("generic") ||
                    fp.startsWith("unknown") ||
                    model.contains("google_sdk") ||
                    model.contains("Emulator") ||
                    model.contains("Android SDK built for x86") ||
                    hw.contains("goldfish") ||
                    hw.contains("ranchu") ||
                    product.contains("sdk") ||
                    product.contains("google_sdk") ||
                    product.contains("sdk_gphone64"))
        } catch (t: Throwable) {
            false
        }
    }
}
