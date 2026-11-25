package com.autodialer.app

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

class SecurePrefsHarnessTest {
    private lateinit var dir: File
    private lateinit var file: File
    private val inline = Executor { it.run() }
    private var now = 1_000_000L

    private class MemLegacy(val data: MutableMap<String, Any?>) : LegacyPrefs {
        var wiped = false
        override fun readAll(): Map<String, Any?> = HashMap(data)
        override fun wipe() { wiped = true; data.clear() }
    }
    /** The real key, but it can be "down" like a busy Keystore. */
    private class Flaky(val real: DataCipher) : DataCipher {
        @Volatile var down = false
        override fun encrypt(plain: ByteArray, aad: ByteArray) = if (down) throw CryptoException("down") else real.encrypt(plain, aad)
        override fun decrypt(blob: ByteArray, aad: ByteArray) = if (down) throw CryptoException("down") else real.decrypt(blob, aad)
    }

    @Before fun setUp() { dir = java.nio.file.Files.createTempDirectory("sp").toFile(); file = File(dir, "prefs_x.enc"); now = 1_000_000L }
    private fun prefs(c: DataCipher = HarnessCipher.shared, legacy: LegacyPrefs? = null, w: Executor = inline) =
        SecurePrefs(file, c, "prefs:x", legacy, w) { now }
    private fun raw() = String(file.readBytes(), Charsets.ISO_8859_1)

    @Test fun `all value types survive a restart`() {
        val p = prefs()
        p.edit().putString("s", "9075034748").putInt("i", 7).putLong("l", 1L shl 40).putBoolean("b", true).putStringSet("set", setOf("a", "b")).apply()
        val q = prefs()
        assertEquals("9075034748", q.getString("s", null)); assertEquals(7, q.getInt("i", 0)); assertEquals(1L shl 40, q.getLong("l", 0))
        assertTrue(q.getBoolean("b", false)); assertEquals(setOf("a", "b"), q.getStringSet("set", null))
        assertTrue(q.contains("s")); assertFalse(q.contains("nope")); assertEquals("def", q.getString("nope", "def"))
    }
    @Test fun `the file on disk holds no key name or value in the clear`() {
        prefs().edit().putString("phone", "9075034748").putBoolean("loggedIn", true).apply()
        assertFalse(raw().contains("9075034748")); assertFalse(raw().contains("phone")); assertFalse(raw().contains("loggedIn"))
    }
    @Test fun `SharedPreferences semantics - null removes, clear runs before the puts, remove wins over earlier put`() {
        val p = prefs(); p.edit().putString("a", "1").putString("b", "2").apply()
        p.edit().putString("a", null).apply(); assertFalse(p.contains("a"))
        // Android rule: clear() is applied FIRST no matter where it is called, then every put - so "c" survives.
        p.edit().putString("c", "3").clear().putString("d", "4").apply()
        assertFalse(p.contains("b")); assertEquals("3", p.getString("c", null)); assertEquals("4", p.getString("d", null))
        p.edit().putString("e", "5").remove("e").apply(); assertFalse(p.contains("e"))
        assertEquals("4", prefs().getString("d", null))                                  // and it persisted
    }
    @Test fun `commit writes before returning, apply is coalesced into one background write`() {
        val q = ArrayDeque<Runnable>(); var writes = 0
        val manual = Executor { q.add(it) }
        val p = prefs(w = manual)
        repeat(500) { p.edit().putInt("n", it).apply() }
        assertEquals("one write queued, not 500", 1, q.size)
        assertFalse(file.exists()); while (q.isNotEmpty()) { q.removeFirst().run(); writes++ }
        assertEquals(499, prefs().getInt("n", -1))
        assertTrue(p.edit().putInt("n", 1).commit()); assertEquals(1, prefs().getInt("n", -1))
    }
    @Test fun `migration - old plaintext values are imported encrypted, then the plaintext copy is wiped`() {
        val legacy = MemLegacy(mutableMapOf("phone" to "9075034748", "loggedIn" to true, "n" to 5, "t" to 99L, "days" to setOf("2026-11-21")))
        val p = prefs(legacy = legacy)
        assertEquals("9075034748", p.getString("phone", null)); assertTrue(p.getBoolean("loggedIn", false)); assertEquals(5, p.getInt("n", 0))
        assertEquals(setOf("2026-11-21"), p.getStringSet("days", null))
        assertTrue("plaintext copy erased", legacy.wiped); assertFalse(raw().contains("9075034748"))
        assertEquals("9075034748", prefs().getString("phone", null))
    }
    @Test fun `migration is NOT wiped when the encrypted write fails - nothing is lost, it retries next launch`() {
        val legacy = MemLegacy(mutableMapOf("phone" to "9075034748"))
        val flaky = Flaky(HarnessCipher.shared); flaky.down = true
        val p = prefs(flaky, legacy); assertEquals("9075034748", p.getString("phone", null))
        assertFalse(legacy.wiped); assertFalse(file.exists())
        flaky.down = false
        val p2 = prefs(flaky, legacy); assertEquals("9075034748", p2.getString("phone", null)); assertTrue(legacy.wiped)
    }
    @Test fun `Keystore briefly down - nothing is overwritten, edits made meanwhile are merged over the real data`() {
        val flaky = Flaky(HarnessCipher.shared)
        prefs(flaky).edit().putString("phone", "9075034748").putString("plan", "MONTHLY").apply()
        val good = file.readBytes()
        flaky.down = true
        val p = prefs(flaky)
        assertEquals("defaults while down", "none", p.getString("phone", "none"))
        p.edit().putString("plan", "YEARLY").putString("extra", "x").apply()
        assertEquals("YEARLY", p.getString("plan", null))                              // visible immediately
        assertArrayEquals("the good file must be untouched while the key is down", good, file.readBytes())
        flaky.down = false; now += 5_000
        assertEquals("9075034748", p.getString("phone", null))                          // real data is back...
        assertEquals("YEARLY", p.getString("plan", null)); assertEquals("x", p.getString("extra", null))   // ...with the offline edits on top
        val reread = prefs(flaky); assertEquals("9075034748", reread.getString("phone", null)); assertEquals("YEARLY", reread.getString("plan", null))
    }
    @Test fun `the key is not hammered on every read while it is down`() {
        val calls = AtomicInteger(); val flaky = object : DataCipher {
            override fun encrypt(plain: ByteArray, aad: ByteArray) = HarnessCipher.shared.encrypt(plain, aad)
            override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray? { calls.incrementAndGet(); throw CryptoException("down") } }
        prefs().edit().putString("a", "1").apply()
        val p = prefs(flaky); repeat(1000) { p.getString("a", null) }
        assertEquals(1, calls.get()); now += 2_001; p.getString("a", null); assertEquals(2, calls.get())
    }
    @Test fun `permanently undecryptable file (key replaced) is treated as empty and the next write replaces it`() {
        prefs().edit().putString("phone", "9075034748").apply()
        val p = prefs(HarnessCipher.cipher())
        assertEquals("none", p.getString("phone", "none")); p.edit().putString("phone", "9822012345").apply()
        assertEquals("9822012345", prefs(HarnessCipher.cipher(HarnessCipherKey.last!!)).getString("phone", null))
    }
    @Test fun `a tampered file is treated as empty, never as a crash`() {
        prefs().edit().putString("phone", "9075034748").apply()
        val b = file.readBytes(); b[b.size - 3] = (b[b.size - 3].toInt() xor 1).toByte(); file.writeBytes(b)
        assertEquals("none", prefs().getString("phone", "none"))
    }
    @Test fun `no key means no write - the value stays in memory only, never in the clear`() {
        val p = prefs(HarnessCipher.broken); p.edit().putString("phone", "9075034748").apply()
        assertEquals("9075034748", p.getString("phone", null)); assertFalse(p.edit().putString("z", "1").commit())
        assertFalse(file.exists())
    }
    @Test fun `one instance per name for the whole process`() {
        val ctx = Context(dir); assertSame(SecurePrefs.open(ctx, "autodialer_auth"), SecurePrefs.open(ctx, "autodialer_auth"))
        assertNotSame(SecurePrefs.open(ctx, "autodialer_auth"), SecurePrefs.open(ctx, "autodialer_license"))
    }
    @Test(timeout = 60_000) fun `eight threads editing at once - every edit is kept and the file matches memory`() {
        val p = prefs(); val go = CountDownLatch(1)
        val ts = (0 until 8).map { t -> Thread { go.await(); repeat(150) { i -> p.edit().putInt("t$t-$i", i).apply() } }.also { it.start() } }
        go.countDown(); ts.forEach { it.join() }; p.flush()
        val fresh = prefs()
        for (t in 0 until 8) for (i in 0 until 150) assertEquals("t$t-$i", i, fresh.getInt("t$t-$i", -1))
    }
}
