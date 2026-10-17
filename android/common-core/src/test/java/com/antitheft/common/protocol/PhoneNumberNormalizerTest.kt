package com.antitheft.common.protocol

import org.junit.Assert.*
import org.junit.Test

class PhoneNumberNormalizerTest {

    @Test
    fun testNormalizeInternationalNumber() {
        val input = "+1 (555) 521-5556"
        val normalized = PhoneNumberNormalizer.normalizeToE164(input)
        assertEquals("+15555215556", normalized)
    }

    @Test
    fun testNormalizeUkNumber() {
        val input = "+44 7700-900077"
        val normalized = PhoneNumberNormalizer.normalizeToE164(input)
        assertEquals("+447700900077", normalized)
    }

    @Test
    fun testNormalizeEmulatorShortPort() {
        val input = "5556"
        val normalized = PhoneNumberNormalizer.normalizeToE164(input)
        assertEquals("+15555215556", normalized)
    }

    @Test
    fun testValidationE164() {
        assertTrue(PhoneNumberNormalizer.isValid("+15555215556"))
        assertTrue(PhoneNumberNormalizer.isValid("+447700900077"))
        assertTrue(PhoneNumberNormalizer.isValid("+4915123456789"))
        assertFalse(PhoneNumberNormalizer.isValid("invalid-text"))
        assertFalse(PhoneNumberNormalizer.isValid("+123")) // Too short for E.164
    }

    @Test
    fun testNormalizeForSmsDispatchNonBlank() {
        val raw = "+15555215556"
        val result = PhoneNumberNormalizer.normalizeForSmsDispatch(raw)
        assertTrue(result.isNotBlank())
    }
}
