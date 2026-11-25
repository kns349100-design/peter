package com.autodialer.app

/**
 * Client-side check for the login screen. The SERVER (Code.gs normalizeEmail) is the authority and also
 * canonicalizes (Gmail dots, +tags); this only stops obviously wrong input before a network call, with the
 * same rules and the same linear-time pattern (no nested quantifiers; length is capped first).
 */
object EmailValidator {
    private val PATTERN = Regex("^[a-z0-9._%+\\-]{1,64}@([a-z0-9\\-]+\\.)+[a-z]{2,24}$")

    /** The address as it should be sent (trimmed, lower-case), or null if it is not a plausible email. */
    fun normalize(input: String?): String? {
        val s = (input ?: "").trim().lowercase()
        if (s.length < 6 || s.length > 254 || !PATTERN.matches(s)) return null
        val at = s.lastIndexOf('@')
        val local = s.substring(0, at)
        val domain = s.substring(at + 1)
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) return null
        if (domain.split('.').any { it.isEmpty() || it.startsWith("-") || it.endsWith("-") }) return null
        val base = local.substringBefore('+').let { if (domain == "gmail.com" || domain == "googlemail.com") it.replace(".", "") else it }
        if (base.isEmpty()) return null
        return s
    }

    /** "rahul.sharma@gmail.com" -> "r***@gmail.com" for on-screen messages. */
    fun mask(email: String): String {
        val at = email.indexOf('@')
        return if (at <= 0) "***" else email.substring(0, 1) + "***" + email.substring(at)
    }
}
