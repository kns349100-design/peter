package com.autodialer.app

object PhoneUtils {
    private val REPEATED_DIGITS = Regex("^(\\d)\\1{9,}$")

    /**
     * Normalizes a phone number to canonical "91XXXXXXXXXX" digits-only form for
     * deduplication and call-history checks.
     */
    fun normalize(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91${digits.substring(1)}"
            digits.length == 12 && digits.startsWith("91") -> digits
            digits.length == 13 && digits.startsWith("091") -> "91${digits.substring(3)}"
            digits.length == 14 && digits.startsWith("0091") -> "91${digits.substring(4)}"
            else -> digits
        }
    }

    /**
     * Validates that a parsed number is a genuine dialable phone number
     * (10 to 13 digits, rejecting all-same repeated digits like 9999999999 or 0000000000).
     */
    fun isValidDialableNumber(phone: String): Boolean {
        val rawDigits = phone.filter { it.isDigit() }
        if (rawDigits.length !in 10..13) return false
        if (REPEATED_DIGITS.matches(rawDigits)) return false
        val normalized = normalize(phone)
        if (normalized.length == 12 && normalized.startsWith("91")) {
            val subscriber = normalized.substring(2)
            if (REPEATED_DIGITS.matches(subscriber)) return false
        }
        return true
    }

    /** Formats a number cleanly for WhatsApp wa.me links (digits with country code, no '+'). */
    fun toWhatsAppDigits(phone: String): String {
        val normalized = normalize(phone)
        return if (normalized.length >= 10) normalized else phone.filter { it.isDigit() }
    }
}
