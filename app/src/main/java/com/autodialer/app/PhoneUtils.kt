package com.autodialer.app

object PhoneUtils {
    private val REPEATED_DIGITS = Regex("^(\\d)\\1{9}$")

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
     * Validates that a parsed number is a genuine dialable Indian mobile or landline
     * number (rejects dates, IDs, repeated digits like 9999999999, and invalid prefixes).
     */
    fun isValidDialableNumber(phone: String): Boolean {
        val normalized = normalize(phone)
        if (normalized.length == 12 && normalized.startsWith("91")) {
            val subscriber = normalized.substring(2)
            if (REPEATED_DIGITS.matches(subscriber)) return false
            // Indian mobile numbers start with 6, 7, 8, or 9; landlines with STD code start with 1-8 (never 0 or 9-prefixed fake sequences)
            val first = subscriber.first()
            if (first !in '2'..'9') return false
            // Reject obvious sequential dummy numbers
            if (subscriber == "1234567890" || subscriber == "9876543210") return false
            return true
        }
        // International numbers with country code (11 to 13 digits, not starting with 0)
        val rawDigits = phone.filter { it.isDigit() }
        return rawDigits.length in 11..13 && !rawDigits.startsWith("0") && !REPEATED_DIGITS.containsMatchIn(rawDigits)
    }

    /** Formats a number cleanly for WhatsApp wa.me links (digits with country code, no '+'). */
    fun toWhatsAppDigits(phone: String): String {
        val normalized = normalize(phone)
        return if (normalized.length >= 10) normalized else phone.filter { it.isDigit() }
    }
}
