package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

class DialJournalCryptoTest {
    private fun file() = File(java.nio.file.Files.createTempDirectory("jc").toFile(), "j.log")
    private fun legacyLine(sid: String, ts: Long, t: Char, i: Int, phone: String): String {
        val body = "J1|$sid|$ts|$t|$i|$phone"; val c = CRC32(); c.update(body.toByteArray()); return "$body|${java.lang.Long.toHexString(c.value)}\n"
    }

    @Test fun `the journal file never contains a phone number, name of the session or record type in the clear`() {
        val f = file(); val j = DialJournal(f, TestCiphers.cipher())
        j.append("session-ABC", JournalType.DIAL, 7, "+919075034748"); j.append("session-ABC", JournalType.SKIP, 8, "9822012345")
        val text = f.readText()
        assertFalse(text.contains("9075034748")); assertFalse(text.contains("9822012345")); assertFalse(text.contains("session-ABC"))
        assertTrue(text.lines().filter { it.isNotEmpty() }.all { it.startsWith("E1:") })
    }
    @Test fun `records round-trip through encryption`() {
        val j = DialJournal(file(), TestCiphers.cipher()); j.append("s", JournalType.DIAL, 3, "+919075034748")
        val r = j.read("s").single(); assertEquals(JournalType.DIAL, r.type); assertEquals(3, r.index); assertEquals("+919075034748", r.phone)
    }
    @Test fun `a journal written with another key yields nothing and does not crash`() {
        val f = file(); DialJournal(f, TestCiphers.cipher()).append("s", JournalType.DIAL, 1, "9111111111")
        assertTrue(DialJournal(f, TestCiphers.cipher()).read("s").isEmpty())
    }
    @Test fun `a record moved or altered by hand is rejected`() {
        val f = file(); val j = DialJournal(f, TestCiphers.cipher()); j.append("s", JournalType.DIAL, 1, "9111111111")
        f.writeText(f.readText().replaceFirst("E1:", "E1:00"))
        assertTrue(j.read("s").isEmpty())
    }
    @Test fun `no key means the append fails, so the engine will not dial`() {
        val f = file(); assertFalse(DialJournal(f, TestCiphers.broken()).append("s", JournalType.DIAL, 1, "9111111111")); assertFalse(f.exists())
    }
    @Test fun `legacy plaintext lines from an older version are still read`() {
        val f = file(); f.writeText(legacyLine("s", 5, 'D', 2, "9111111111") + legacyLine("s", 6, 'S', 3, "9222222222"))
        val j = DialJournal(f, TestCiphers.cipher())
        assertEquals(listOf(2, 3), j.read("s").map { it.index }); assertTrue(j.hasLegacyPlaintext())
    }
    @Test fun `encryptLegacy rewrites everything encrypted, keeps every record, mixes with new appends`() {
        val f = file(); f.writeText(legacyLine("s", 5, 'D', 2, "9111111111"))
        val j = DialJournal(f, TestCiphers.cipher()); j.append("s", JournalType.DIAL, 9, "9333333333")   // mixed file
        assertTrue(j.encryptLegacy())
        assertFalse(j.hasLegacyPlaintext()); assertFalse(f.readText().contains("9111111111")); assertFalse(f.readText().contains("J1|"))
        assertEquals(listOf(2, 9), j.read("s").map { it.index })
    }
    @Test fun `a torn encrypted line is fenced off and later records stay valid`() {
        val f = file(); val j = DialJournal(f, TestCiphers.cipher()); j.append("s", JournalType.DIAL, 1, "9111111111")
        f.appendText("E1:4144450100aabb")                                 // power cut mid-append
        assertEquals(1, j.read("s").size)
        assertTrue(j.append("s", JournalType.DIAL, 2, "9222222222")); assertEquals(listOf(1, 2), j.read("s").map { it.index })
    }
}
