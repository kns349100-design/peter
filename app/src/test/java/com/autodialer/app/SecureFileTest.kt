package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SecureFileTest {
    private fun dir() = java.nio.file.Files.createTempDirectory("sf").toFile()
    private fun contains(f: File, s: String) = String(f.readBytes(), Charsets.ISO_8859_1).contains(s)

    @Test fun `write then read gives the data back and the file holds no plaintext`() {
        val c = TestCiphers.cipher(); val f = File(dir(), "s.json")
        assertTrue(SecureFile.write(f, """{"phone":"9075034748"}""".toByteArray(), c, "a"))
        val r = SecureFile.read(f, c, "a") as SecureFile.Read.Data
        assertEquals("""{"phone":"9075034748"}""", String(r.bytes)); assertFalse(r.wasPlaintext)
        assertFalse(contains(f, "9075034748"))
    }
    @Test fun `missing file`() = assertTrue(SecureFile.read(File(dir(), "nope"), TestCiphers.cipher(), "a") is SecureFile.Read.Missing)
    @Test fun `an old plaintext file is still readable and flagged for upgrade`() {
        val f = File(dir(), "old.json"); f.writeText("""{"phone":"9075034748"}""")
        val r = SecureFile.read(f, TestCiphers.cipher(), "a") as SecureFile.Read.Data
        assertTrue(r.wasPlaintext); assertEquals("""{"phone":"9075034748"}""", String(r.bytes))
    }
    @Test fun `tampered, wrong key and wrong purpose are Unreadable`() {
        val c = TestCiphers.cipher(); val f = File(dir(), "s"); SecureFile.write(f, "x".toByteArray(), c, "a")
        assertTrue(SecureFile.read(f, TestCiphers.cipher(), "a") is SecureFile.Read.Unreadable)      // key lost / replaced
        assertTrue(SecureFile.read(f, c, "b") is SecureFile.Read.Unreadable)                          // moved to another purpose
        val b = f.readBytes(); b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte(); f.writeBytes(b)
        assertTrue(SecureFile.read(f, c, "a") is SecureFile.Read.Unreadable)                          // modified
    }
    @Test fun `no key means no write - never a plaintext fallback`() {
        val f = File(dir(), "s"); assertFalse(SecureFile.write(f, "9075034748".toByteArray(), TestCiphers.broken(), "a"))
        assertFalse(f.exists()); assertFalse(File(f.path + ".tmp").exists())
    }
    @Test fun `a keystore that is down is KeyUnavailable (transient), not Unreadable and not corrupt-and-deleted`() {
        val c = TestCiphers.cipher(); val f = File(dir(), "s"); SecureFile.write(f, "x".toByteArray(), c, "a")
        assertTrue(SecureFile.read(f, TestCiphers.broken(), "a") is SecureFile.Read.KeyUnavailable)
        assertTrue("file must be untouched", f.exists()); assertTrue(SecureFile.read(f, c, "a") is SecureFile.Read.Data)
    }
    @Test fun `crash between delete and rename - the finished encrypted temp file is still read`() {
        val c = TestCiphers.cipher(); val f = File(dir(), "s"); SecureFile.write(f, "hello".toByteArray(), c, "a")
        f.copyTo(File(f.path + ".tmp")); f.delete()
        assertEquals("hello", String((SecureFile.read(f, c, "a") as SecureFile.Read.Data).bytes))
    }
}
