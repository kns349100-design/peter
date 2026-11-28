package com.autodialer.app

/**
 * Extracts and validates dialable phone numbers from pasted text, CSV/TXT files,
 * and OCR output while filtering out dates, invoice IDs, and malformed fragments.
 */
object NumberParser {

    private val plainRun = Regex("(?<!\\d)\\+?\\d{10,13}(?!\\d)")
    private val spaced = Regex("(?<!\\d)(\\+?\\d[\\d\\-\\s.()]{8,18}\\d)(?!\\d)")
    private val dateLike = Regex("^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{2,4}$")
    private val separators = Regex("[\\s\\-.()]")

    fun extractPhoneNumbers(text: String): Set<String> {
        val raw = linkedSetOf<String>()

        plainRun.findAll(text).forEach { match ->
            val token = match.value
            if (PhoneUtils.isValidDialableNumber(token)) {
                raw.add(token)
            }
        }

        spaced.findAll(text).forEach { match ->
            val rawMatch = match.value.trim()
            if (dateLike.matches(rawMatch)) return@forEach
            val cleaned = rawMatch.replace(separators, "")
            val digitsOnly = cleaned.replace("+", "")
            if (digitsOnly.length in 10..13 && digitsOnly.all { it.isDigit() } && PhoneUtils.isValidDialableNumber(cleaned)) {
                raw.add(cleaned)
            }
        }

        val seenNormalized = mutableSetOf<String>()
        val results = linkedSetOf<String>()
        for (candidate in raw) {
            val normalized = PhoneUtils.normalize(candidate)
            if (seenNormalized.add(normalized)) {
                results.add(candidate)
            }
        }
        return results
    }

    fun extractLeadsFromLine(line: String, startIndex: Int): List<Lead> {
        val candidates = extractPhoneNumbers(line).toList()
        if (candidates.isEmpty()) return emptyList()

        var leftover = spaced.replace(line) { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 10..13 && !dateLike.matches(m.value.trim())) " " else m.value
        }
        leftover = plainRun.replace(leftover, " ")
        leftover = leftover.trim(' ', ',', '-', '|', '\t', ':', ';', '(', ')', '[', ']', '+', '.').trim()
        val label = leftover.split(",")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it.any { c -> c.isLetter() } }

        return candidates.mapIndexedNotNull { i, token ->
            if (!PhoneUtils.isValidDialableNumber(token)) return@mapIndexedNotNull null
            val finalLabel = when {
                !label.isNullOrBlank() && candidates.size == 1 -> label
                !label.isNullOrBlank() -> "$label${i + 1}"
                else -> "Lead ${startIndex + i}"
            }
            Lead(finalLabel, token)
        }
    }
}
