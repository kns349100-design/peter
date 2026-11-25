package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class AesGcmCipherTest {
    private val aad = "purpose".toByteArray()
    private fun indexOf(h: ByteArray, n: ByteArray): Int { outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }; return -1 }

    @Test fun `round trip for many sizes including empty and 1MB`() {
        val c = TestCiphers.cipher()
        for (n in listOf(0, 1, 15, 16, 17, 1000, 1_000_000)) {
            val plain = ByteArray(n) { (it * 31).toByte() }
            assertArrayEquals("size $n", plain, c.decrypt(c.encrypt(plain, aad), aad))
        }
    }
    @Test fun `output is marked, longer than the input, and does not contain the plaintext`() {
        val c = TestCiphers.cipher(); val plain = "+919075034748 Rahul Sharma".toByteArray()
        val blob = c.encrypt(plain, aad)
        assertTrue(CipherFormat.looksEncrypted(blob)); assertEquals(plain.size + 4 + 12 + 16, blob.size)
        assertEquals(-1, indexOf(blob, "9075034748".toByteArray())); assertEquals(-1, indexOf(blob, "Rahul".toByteArray()))
    }
    @Test fun `every message gets a fresh IV - same plaintext never gives the same ciphertext`() {
        val c = TestCiphers.cipher(); val seen = HashSet<String>()
        repeat(20_000) { assertTrue("IV reused", seen.add(c.encrypt("same".toByteArray(), aad).copyOfRange(4, 16).joinToString(","))) }
    }
    @Test fun `flipping ANY single byte makes decryption fail`() {
        val c = TestCiphers.cipher(); val blob = c.encrypt("secret numbers".toByteArray(), aad)
        for (i in blob.indices) {
            val t = blob.copyOf(); t[i] = (t[i].toInt() xor 1).toByte()
            assertNull("byte $i", c.decrypt(t, aad))
        }
    }
    @Test fun `truncated or extended blobs fail`() {
        val c = TestCiphers.cipher(); val blob = c.encrypt("secret".toByteArray(), aad)
        assertNull(c.decrypt(blob.copyOf(blob.size - 1), aad)); assertNull(c.decrypt(blob + byteArrayOf(0), aad)); assertNull(c.decrypt(blob.copyOf(10), aad))
    }
    @Test fun `another key cannot read it`() {
        val blob = TestCiphers.cipher().encrypt("secret".toByteArray(), aad)
        assertNull(TestCiphers.cipher().decrypt(blob, aad))
    }
    @Test fun `a blob is bound to its purpose - same key, different AAD fails`() {
        val c = TestCiphers.cipher(); val blob = c.encrypt("secret".toByteArray(), "session_v2".toByteArray())
        assertNull(c.decrypt(blob, "journal".toByteArray())); assertNotNull(c.decrypt(blob, "session_v2".toByteArray()))
    }
    @Test fun `plaintext and garbage are rejected, not decoded`() {
        val c = TestCiphers.cipher()
        assertNull(c.decrypt("{\"leads\":[]}".toByteArray(), aad)); assertNull(c.decrypt(ByteArray(0), aad)); assertNull(c.decrypt(ByteArray(64), aad))
    }
    @Test fun `unavailable key is an error, never silently treated as plaintext or as garbage`() {
        val c = TestCiphers.broken()
        try { c.encrypt("x".toByteArray(), aad); fail() } catch (e: CryptoException) { }
        val blob = TestCiphers.cipher().encrypt("x".toByteArray(), aad)
        try { c.decrypt(blob, aad); fail() } catch (e: CryptoException) { }
    }
    @Test fun `the key is 256 bit`() = assertEquals(32, TestCiphers.newKey().encoded.size)

    @Test(timeout = 60_000) fun `eight threads encrypting and decrypting concurrently`() {
        val c = TestCiphers.cipher(); val bad = AtomicInteger(); val go = CountDownLatch(1)
        val ts = (0 until 8).map { t -> Thread { go.await(); repeat(1000) { i ->
            val p = "t$t-$i".toByteArray(); if (!p.contentEquals(c.decrypt(c.encrypt(p, aad), aad))) bad.incrementAndGet() } }.also { it.start() } }
        go.countDown(); ts.forEach { it.join() }; assertEquals(0, bad.get())
    }
}
