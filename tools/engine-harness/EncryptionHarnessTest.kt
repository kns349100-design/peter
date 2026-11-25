package com.autodialer.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor
import java.util.zip.CRC32

/** The real SessionStore / DialJournal / FileStore on a real temp dir, with real AES-256-GCM. */
class EncryptionHarnessTest {
    private lateinit var dir: File
    private val inline = Executor { it.run() }
    private fun num(i: Int) = "9%09d".format(i + 300_000_000)
    private fun leads(n: Int) = List(n) { Lead("Name$it", num(it)) }
    private fun everyFileText(): String = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

    @Before fun setUp() { Handler.queue.clear(); Build.VERSION.SDK_INT = 31; dir = java.nio.file.Files.createTempDirectory("enc").toFile(); FileStore.cipherFactory = { HarnessCipher.shared } }

    private fun engine(cipher: DataCipher = HarnessCipher.shared): Triple<CallEngine, Activity, SessionStore> {
        val act = Activity(); val store = SessionStore(Context(dir), inline, dir, cipher)
        return Triple(CallEngine(act, store, CallLogStore(), EngineHarnessTest.Fake()), act, store)
    }

    @Test fun `after a real session NO phone number or name is readable anywhere on disk`() {
        val (e, act, _) = engine(); e.loadLeads(leads(6), "Diwali"); e.start()
        act.telephony.emit(2); act.telephony.emit(0); Handler.drain(); e.selectOutcome("INTERESTED")
        e.stop()
        assertTrue("files must exist", File(dir, "session_v2.json").exists() && File(dir, "dial_journal.log").exists())
        val all = everyFileText()
        for (i in 0 until 6) { assertFalse("number $i leaked", all.contains(num(i))); assertFalse("name $i leaked", all.contains("Name$i")) }
        assertFalse(all.contains("Diwali")); assertFalse(all.contains("INTERESTED"))
        e.teardown()
    }

    @Test fun `restart still restores the session from the encrypted files`() {
        val (e1, _, _) = engine(); e1.loadLeads(leads(5), "t"); e1.start(); e1.teardown()
        val (e2, act2, _) = engine(); assertTrue(e2.restoreIfAvailable())
        assertEquals(CallSequencer.Status.COMPLETED, e2.leads[0].status); assertEquals(5, e2.leads.size)
        e2.start(); assertTrue(act2.dialed.isEmpty()); e2.teardown()
    }

    @Test fun `lost key - nothing to restore, no crash, files untouched, a new list works`() {
        val (e1, _, _) = engine(); e1.loadLeads(leads(4), "t"); e1.start(); e1.teardown()
        val before = File(dir, "session_v2.json").readBytes()
        val other = HarnessCipher.cipher()
        val (e2, act2, store2) = engine(other)
        assertFalse(e2.restoreIfAvailable()); assertTrue(store2.lastLoadUnreadable)
        assertArrayEquals("must not delete or overwrite what it cannot read", before, File(dir, "session_v2.json").readBytes())
        e2.loadLeads(leads(3), "fresh"); e2.start()
        assertEquals(listOf(num(0)), act2.dialed); e2.teardown()
    }

    @Test fun `Keystore down - the app refuses to dial instead of writing anything in the clear`() {
        val (e, act, _) = engine(HarnessCipher.broken)
        e.loadLeads(leads(3), "t"); e.start()
        assertTrue(act.dialed.isEmpty())
        assertFalse("nothing may be written without a key", dir.listFiles()!!.any { it.length() > 0 })
        e.teardown()
    }

    private fun legacyJ1(sid: String, t: Char, i: Int, phone: String): String {
        val body = "J1|$sid|1|$t|$i|$phone"; val c = CRC32(); c.update(body.toByteArray()); return "$body|${java.lang.Long.toHexString(c.value)}\n"
    }

    @Test fun `upgrade from the plaintext Round-4 files - nothing lost, nothing left in the clear`() {
        val leadsJson = JSONArray(); for (i in 0 until 4) leadsJson.put(JSONObject().put("name", "Name$i").put("phone", num(i)).put("status", "PENDING").put("outcome", ""))
        File(dir, "session_v2.json").writeText(JSONObject().put("v", 2).put("sessionId", "old-session").put("savedAt", 1).put("sessionName", "legacy")
            .put("currentIndex", 0).put("delaySeconds", 2).put("batchTarget", 0).put("pendingOutcomeIndex", -1).put("pendingLogDate", "").put("pendingLogIndex", -1)
            .put("awaitingWhatsAppReturn", false).put("leads", leadsJson).toString())
        File(dir, "dial_journal.log").writeText(legacyJ1("old-session", 'D', 1, num(1)) + legacyJ1("old-session", 'S', 2, num(2)))
        assertTrue(everyFileText().contains(num(1)))                                       // sanity: it IS plaintext now

        val (e, act, _) = engine(); assertTrue(e.restoreIfAvailable())
        assertEquals(CallSequencer.Status.COMPLETED, e.leads[1].status)                    // journal record applied
        assertEquals(CallSequencer.Status.SKIPPED, e.leads[2].status)
        val all = everyFileText()
        for (i in 0 until 4) { assertFalse("number $i still in clear", all.contains(num(i))); assertFalse(all.contains("Name$i")) }
        assertFalse(all.contains("J1|"))
        val (e2, _, _) = engine(); assertTrue(e2.restoreIfAvailable())                     // and a second restart still reads it
        assertEquals(CallSequencer.Status.COMPLETED, e2.leads[1].status); assertEquals(CallSequencer.Status.SKIPPED, e2.leads[2].status)
        e.teardown(); e2.teardown()
    }

    // ---------------- FileStore (call-log day files) ----------------

    @Test fun `FileStore round trip, encrypted on disk`() {
        val ctx = Context(dir); FileStore.writeString(ctx, "calllog_2026-11-21.json", """[{"phone":"9075034748"}]""")
        assertEquals("""[{"phone":"9075034748"}]""", FileStore.readString(ctx, "calllog_2026-11-21.json"))
        assertFalse(everyFileText().contains("9075034748"))
    }
    @Test fun `FileStore reads an old plaintext file, and encryptLegacyFiles converts it`() {
        val ctx = Context(dir); File(dir, "calllog_2026-01-01.json").writeText("""[{"phone":"9075034748"}]""")
        File(dir, "other.txt").writeText("9075034748")
        assertEquals("""[{"phone":"9075034748"}]""", FileStore.readString(ctx, "calllog_2026-01-01.json"))
        assertEquals(1, FileStore.encryptLegacyFiles(ctx))
        assertFalse(String(File(dir, "calllog_2026-01-01.json").readBytes()).contains("9075034748"))
        assertEquals("""[{"phone":"9075034748"}]""", FileStore.readString(ctx, "calllog_2026-01-01.json"))
        assertEquals("unrelated files are not touched", "9075034748", File(dir, "other.txt").readText())
        assertEquals("idempotent", 0, FileStore.encryptLegacyFiles(ctx))
    }
    @Test fun `FileStore - a file encrypted under another key reads as null`() {
        val ctx = Context(dir); FileStore.writeString(ctx, "calllog_x.json", "secret")
        FileStore.cipherFactory = { HarnessCipher.cipher() }
        assertNull(FileStore.readString(ctx, "calllog_x.json"))
    }
    @Test fun `FileStore - a blob copied from one file name to another is rejected`() {
        val ctx = Context(dir); FileStore.writeString(ctx, "calllog_a.json", "secret")
        File(dir, "calllog_a.json").copyTo(File(dir, "calllog_b.json"))
        assertNull(FileStore.readString(ctx, "calllog_b.json"))
    }
    @Test(timeout = 60_000) fun `migration running while the app keeps saving never overwrites a newer save`() {
        val ctx = Context(dir)
        for (d in 0 until 40) File(dir, "calllog_old$d.json").writeText("""[{"phone":"900000$d"}]""")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false); var version = 0
        val writer = Thread { while (!stop.get()) { version++; FileStore.writeString(ctx, "calllog_old7.json", "newer-$version") } }
        writer.start(); repeat(30) { FileStore.encryptLegacyFiles(ctx) }; stop.set(true); writer.join()
        assertEquals("newer-$version", FileStore.readString(ctx, "calllog_old7.json"))
        assertEquals("""[{"phone":"90000039"}]""", FileStore.readString(ctx, "calllog_old39.json"))
    }
    @Test fun `stale CSV exports are deleted, fresh ones kept`() {
        val ctx = Context(dir); val cache = ctx.cacheDir; val exp = File(cache, "exports").apply { mkdirs() }
        val old = File(exp, "call_log_a.csv").apply { writeText("9075034748"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        val fresh = File(exp, "call_log_b.csv").apply { writeText("x") }
        val loose = File(cache, "call_log_old.csv").apply { writeText("9075034748"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        val unrelated = File(cache, "image_cache.bin").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        assertEquals(2, FileStore.purgeStaleExports(ctx))
        assertFalse(old.exists()); assertFalse(loose.exists()); assertTrue(fresh.exists()); assertTrue(unrelated.exists())
    }
}
