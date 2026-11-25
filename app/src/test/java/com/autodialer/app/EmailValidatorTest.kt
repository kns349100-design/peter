package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test

/** Same good/bad vectors as backend/tests/integration.test.js ("Email normalization"), so app and server agree. */
class EmailValidatorTest {
    private val bad = listOf("", "rahul", "rahul@", "@example.com", "rahul@example", "a b@example.com", "rahul@@example.com", "rahul@exa mple.com",
        ".rahul@example.com", "rahul.@example.com", "ra..hul@example.com", "rahul@-example.com", "rahul@example-.com", "rahul@example.c",
        "+tag@example.com", "rahul@example.com\nBcc: x@y.com", "<script>@example.com", "x'--@example.com", "a".repeat(65) + "@example.com", "a@" + "b".repeat(250) + ".com")

    @Test fun `server-rejected addresses are rejected here too`() { for (b in bad) assertNull("accepted: $b", EmailValidator.normalize(b)) }
    @Test fun `normal addresses pass, trimmed and lower-cased`() {
        assertEquals("rahul@example.com", EmailValidator.normalize("  Rahul@Example.COM "))
        assertEquals("rahul+promo@example.com", EmailValidator.normalize("rahul+promo@example.com"))
        assertEquals("r.a.h.u.l@gmail.com", EmailValidator.normalize("r.a.h.u.l@gmail.com"))
        assertEquals("a.b@sub.domain.co.in", EmailValidator.normalize("a.b@sub.domain.co.in"))
    }
    @Test fun `null is rejected`() = assertNull(EmailValidator.normalize(null))
    @Test fun `masking shows only the first letter`() {
        assertEquals("r***@gmail.com", EmailValidator.mask("rahul.sharma@gmail.com")); assertEquals("***", EmailValidator.mask("nonsense"))
        assertFalse(EmailValidator.mask("rahul.sharma@gmail.com").contains("sharma"))
    }
    @Test(timeout = 5_000) fun `adversarial input is linear`() {
        for (s in listOf("a".repeat(300) + "@", "a@" + "b.".repeat(120), "a@" + "-".repeat(240) + ".com", "a.".repeat(130) + "@x.com", "9".repeat(1_000_000))) EmailValidator.normalize(s)
    }
}
