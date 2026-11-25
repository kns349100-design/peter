package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test

class PiiScrubberTest {
    private val phones = listOf("9075034748", "+919075034748", "+91 90750 34748", "090750-34748", "(+91) 90750 34748", "+91-9075034748", "0091 9075034748", "90750.34748")

    @Test fun `every common phone format is masked and only the last two digits survive`() {
        for (p in phones) {
            val out = PiiScrubber.scrub("Calling $p now")
            assertFalse("$p leaked: $out", Regex("""\d{3,}""").containsMatchIn(out))
            assertTrue("$p -> $out", out.startsWith("Calling ") && out.endsWith(" now") && out.contains("48"))
        }
    }
    @Test fun `masking keeps the plus sign and the last two digits`() = assertEquals("+**********48", PiiScrubber.maskPhone("+919075034748"))
    @Test fun `several numbers in one message`() {
        val out = PiiScrubber.scrub("A 9075034748, B +91 98220 12345; C 9111111111")
        assertFalse(Regex("""\d{3,}""").containsMatchIn(out))
    }
    @Test fun `short numbers, dates and times are left alone`() {
        val t = "OTP 123456 on 2026-11-21 at 10:45, retry 3 of 5, lead 1200"
        assertEquals(t, PiiScrubber.scrub(t))
    }
    @Test fun `very long digit runs (card-like) are masked too`() = assertFalse(Regex("""\d{3,}""").containsMatchIn(PiiScrubber.scrub("card 4111111111111111 end")))
    @Test fun `email addresses are masked`() = assertEquals("mail r***@example.com ok", PiiScrubber.scrub("mail rahul.sharma@example.com ok"))
    @Test fun `idempotent - scrubbing twice changes nothing more`() {
        for (p in phones) { val once = PiiScrubber.scrub("x $p y a@b.co"); assertEquals(once, PiiScrubber.scrub(once)) }
    }
    @Test fun `email edge cases`() {
        assertEquals("a***@b.co.", PiiScrubber.scrub("ab@b.co."))                       // trailing sentence dot is not part of the domain
        assertEquals("x @ y", PiiScrubber.scrub("x @ y")); assertEquals("user@localhost", PiiScrubber.scrub("user@localhost"))
        assertEquals("r***@a.com and s***@b.org", PiiScrubber.scrub("rahul@a.com and sita@b.org"))
        assertEquals("a@b.c", PiiScrubber.scrub("a@b.c"))                              // TLD needs 2+ letters
    }
    @Test fun `null and empty`() { assertEquals("", PiiScrubber.scrub(null)); assertEquals("", PiiScrubber.scrub("")) }
    @Test fun `non-ASCII text survives`() = assertEquals("नमस्ते ********48 → ok", PiiScrubber.scrub("नमस्ते 9075034748 → ok"))

    @Test(timeout = 20_000) fun `adversarial input cannot stall it - linear time`() {
        val inputs = listOf("1 ".repeat(150_000), "9".repeat(1_000_000), "+".repeat(500_000), "1-".repeat(200_000) + "x", "(".repeat(300_000) + "1", "1 " + " ".repeat(500_000) + "1", "a@".repeat(100_000))
        for (s in inputs) { val t0 = System.nanoTime(); PiiScrubber.scrub(s); assertTrue("slow: ${(System.nanoTime() - t0) / 1_000_000}ms", (System.nanoTime() - t0) < 4_000_000_000L) }
    }
}
