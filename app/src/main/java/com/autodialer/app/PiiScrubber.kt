package com.autodialer.app

/**
 * Masks personal data in any text that could leave the app's own screens: logs, diagnostics, error messages.
 *  "+91 98765 43210" -> "+**********10"   (only the last 2 digits survive)
 *  "rahul.sharma@x.com" -> "r***@x.com"
 * ISO dates (2026-11-21) are not phone numbers and are left alone. Linear-time (no nested quantifiers), so
 * adversarial input cannot stall the app.
 */
object PiiScrubber {
    private val PHONE = Regex("""\+?\d[\d \t().\-]{5,24}\d""")
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    fun maskPhone(p: String): String {
        val digits = p.count { it.isDigit() }
        if (digits <= 2) return "*".repeat(p.length.coerceAtLeast(1))
        val last2 = p.filter { it.isDigit() }.takeLast(2)
        return (if (p.trimStart().startsWith("+")) "+" else "") + "*".repeat(digits - 2) + last2
    }

    private fun isLocal(c: Char) = c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '_' || c == '%' || c == '+' || c == '-'
    private fun isDomain(c: Char) = c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '-'

    /**
     * Hand-written, bounded scan instead of a regex: the classic email regex (`[\w.+-]+@...`) re-scans a long run of
     * letters/digits from EVERY start position when there is no '@' (quadratic - a 1M-digit string froze it).
     * Here every '@' looks at most 64 chars left and 255 right, so the whole pass is linear.
     */
    private fun maskEmails(text: String): String {
        var at = text.indexOf('@')
        if (at < 0) return text
        val sb = StringBuilder(text.length)
        var copied = 0
        while (at >= 0) {
            var start = at
            while (start > copied && at - start < 64 && isLocal(text[start - 1])) start--
            var end = at + 1
            while (end < text.length && end - at <= 255 && isDomain(text[end])) end++
            val domain = text.substring(at + 1, end).trimEnd('.', '-')
            val dot = domain.lastIndexOf('.')
            if (start < at && dot > 0 && domain.length - dot - 1 >= 2 && domain.substring(dot + 1).all { it.isLetter() && it.code < 128 }) {
                sb.append(text, copied, start).append(text[start]).append("***@").append(domain)
                copied = at + 1 + domain.length
            }
            at = text.indexOf('@', maxOf(at + 1, copied))
        }
        sb.append(text, copied, text.length)
        return sb.toString()
    }

    fun scrub(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val phones = PHONE.replace(text) { m ->
            val digits = m.value.count { it.isDigit() }
            if (digits < 7 || ISO_DATE.matches(m.value)) m.value else maskPhone(m.value)   // 7+ digits: phones, card-like numbers, anything long
        }
        return maskEmails(phones)
    }
}
