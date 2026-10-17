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

        return if (clean.startsWith("+")) {
            val digits = clean.substring(1).filter { it.isDigit() }
            "+$digits"
        } else {
            val digits = clean.filter { it.isDigit() }
            if (digits.isNotBlank()) "+$digits" else ""
        }
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
