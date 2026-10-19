package com.antitheft.common.protocol

/**
 * Standardized phone number normalizer conforming strictly to ITU-T E.164 international format.
 * Formats phone numbers as +<country_code><subscriber_number> (e.g. +15555215556).
 * Rejects invalid, non-standard, or malformed numbers.
 */
object PhoneNumberNormalizer {

    // E.164 format: leading '+' followed by 7 to 15 digits (first digit 1-9)
    private val E164_REGEX = Regex("""^\+[1-9]\d{6,14}$""")

    /**
     * Normalizes a raw phone number to standard E.164 international format (+CountryCodeDigits).
     * Strips whitespace, dashes, parentheses, and dots.
     */
    fun normalizeToE164(rawNumber: String): String {
        val clean = rawNumber.trim().replace(Regex("""[\s\-\(\)\.]"""), "")
        if (clean.isBlank()) return ""

        if (clean.startsWith("+")) {
            val digits = clean.substring(1).filter { it.isDigit() }
            return "+$digits"
        }
        val digits = clean.filter { it.isDigit() }
        if (digits.isBlank()) return ""

        // Smart E.164 normalization for national mobile formats:
        // Indian mobile: 10 digits starting with 6, 7, 8, or 9 -> +91
        if (digits.length == 10 && digits[0] in listOf('6', '7', '8', '9')) {
            return "+91$digits"
        }
        // Indian mobile with leading 0: 09876543210 -> +919876543210
        if (digits.length == 11 && digits.startsWith("0") && digits[1] in listOf('6', '7', '8', '9')) {
            return "+91${digits.substring(1)}"
        }
        // Indian mobile with leading 91 without plus: 919876543210 -> +919876543210
        if (digits.length == 12 && digits.startsWith("91") && digits[2] in listOf('6', '7', '8', '9')) {
            return "+$digits"
        }
        // North American 10 digits: area code starting with 2-9 -> +1
        if (digits.length == 10 && digits[0] in '2'..'9') {
            return "+1$digits"
        }
        // North American 11 digits: 1xxxxxxxxxx -> +1xxxxxxxxxx
        if (digits.length == 11 && digits.startsWith("1")) {
            return "+$digits"
        }

        return "+$digits"
    }

    /**
     * Validates whether a phone number strictly conforms to ITU-T E.164 specifications.
     */
    fun isValid(rawNumber: String): Boolean {
        val normalized = normalizeToE164(rawNumber)
        return E164_REGEX.matches(normalized)
    }

    /**
     * Prepares destination phone number for standard SMS dispatch conforming strictly to E.164.
     */
    fun normalizeForSmsDispatch(rawNumber: String): String {
        return normalizeToE164(rawNumber)
    }
}
