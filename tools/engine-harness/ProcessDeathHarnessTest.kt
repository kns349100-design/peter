package com.autodialer.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.telephony.TelephonyManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor
import kotlin.random.Random

/** A background writer that can be told to "die": everything still queued is lost, like a SIGKILL / OOM. */
class ManualExecutor : Executor {
    private val q = ArrayDeque<Runnable>()
    @Volatile var passthrough = false          // a freshly started process has a working writer while it restores
    override fun execute(r: Runnable) { if (passthrough) r.run() else synchronized(this) { q.add(r) } }
    @Synchronized fun runAll() { while (q.isNotEmpty()) q.removeFirst().run() }
    @Synchronized fun lose() { q.clear() }
}

class FakeLock : WakeLockLike {
    @Volatile var held = false; var acquires = 0
    override val isHeld get() = held
    override fun acquire(timeoutMs: Long) { held = true; acquires++ }
    override fun release() { held = false }
}
class FakeHost : ServiceHost {
    @Volatile var running = false; var starts = 0; var failStart: RuntimeException? = null
    override fun start(label: String) { failStart?.let { throw it }; running = true; starts++ }
    override fun update(label: String) {}
    override fun stop() { running = false }
}

/** Real CallEngine + real SessionStore/journal on a real temp dir. "Process death" = drop everything in memory. */
class ProcessDeathHarnessTest {
    private lateinit var dir: File
    private val allProcs = mutableListOf<Proc>()
    private fun num(i: Int) = "9%09d".format(i + 200_000_000)
    private fun leads(n: Int) = List(n) { Lead("L$it", num(it)) }
    private val OFF = TelephonyManager.CALL_STATE_OFFHOOK
    private val IDLE = TelephonyManager.CALL_STATE_IDLE

    inner class Proc(lossy: Boolean = true, val keepAlive: SessionKeepAlive = SessionKeepAlive.NONE, val cipher: DataCipher = HarnessCipher.shared) {
        val act = Activity()
        val writer = ManualExecutor()
        val store = SessionStore(Context(dir), if (lossy) writer else Executor { it.run() }, dir, cipher)
        val log = CallLogStore()        // fresh & empty: the call log gives NO protection in these tests
        val fake = EngineHarnessTest.Fake()
        val engine = CallEngine(act, store, log, fake, keepAlive)
        init { allProcs.add(this) }
        fun flushWrites() = writer.runAll()
        fun call() { act.telephony.emit(OFF); act.telephony.emit(IDLE); Handler.drain() }
        /** SIGKILL: queued checkpoint writes are lost, nothing is torn down gracefully. */
        fun kill() { writer.lose(); engine.teardown() /* only to stop our helper thread */ }
    }

    @Before fun setUp() { Handler.queue.clear(); Build.VERSION.SDK_INT = 31; dir = java.nio.file.Files.createTempDirectory("ad").toFile() }
    @After fun tearDown() { allProcs.forEach { it.engine.teardown() } }

    private fun allDialed() = allProcs.flatMap { it.act.dialed.toList() }

    @Test fun `kill right after the dial, before ANY checkpoint reached disk - the number is never offered again`() {
        val p1 = Proc(); p1.engine.loadLeads(leads(5), "t"); p1.flushWrites()   // initial checkpoint only
        p1.engine.start()                                                       // lead 0 dialed, journal fsynced
        assertEquals(listOf(num(0)), p1.act.dialed)
        p1.kill()                                                               // checkpoint saying "lead 0 CALLING" is lost

        val p2 = Proc(lossy = false)
        assertTrue(p2.engine.restoreIfAvailable())
        assertEquals(CallSequencer.Status.COMPLETED, p2.engine.leads[0].status)
        p2.engine.start()
        assertEquals("must ask for the outcome, not dial", listOf(0), p2.fake.awaiting)
        assertTrue(p2.act.dialed.isEmpty())
        p2.engine.selectOutcome("X")
        assertEquals(listOf(num(1)), p2.act.dialed)
        assertEquals(allDialed().distinct(), allDialed())
    }

    @Test fun `kill with leads 0-3 done and a stale checkpoint - none of them is dialed again`() {
        val p1 = Proc(); p1.engine.loadLeads(leads(10), "t"); p1.flushWrites()
        p1.engine.start()
        for (i in 0 until 3) { p1.call(); p1.engine.selectOutcome("X") }       // 0,1,2 done, 3 in flight; checkpoint never flushed
        p1.kill()
        val p2 = Proc(lossy = false); assertTrue(p2.engine.restoreIfAvailable())
        for (i in 0..3) assertEquals("lead $i", CallSequencer.Status.COMPLETED, p2.engine.leads[i].status)
        p2.engine.start(); p2.engine.selectOutcome("X")
        assertEquals(listOf(num(4)), p2.act.dialed)
    }

    @Test fun `kill in the middle of a journal append - torn line ignored and later appends still valid`() {
        val p1 = Proc(); p1.engine.loadLeads(leads(6), "t"); p1.flushWrites()
        p1.engine.start(); p1.kill()
        File(dir, "dial_journal.log").appendText("J1|garbage|123|D|4|9000000")  // power cut mid-write, no newline
        val p2 = Proc(lossy = false); assertTrue(p2.engine.restoreIfAvailable())
        p2.engine.start(); p2.engine.selectOutcome("X")                         // writes a NEW record after the torn one
        val p3 = Proc(lossy = false); assertTrue(p3.engine.restoreIfAvailable())
        assertEquals(CallSequencer.Status.COMPLETED, p3.engine.leads[1].status) // lead 1's record survived the torn line
    }

    @Test fun `corrupt or truncated checkpoint never crashes the app`() {
        val p1 = Proc(lossy = false); p1.engine.loadLeads(leads(5), "t")
        val f = File(dir, "session_v2.json"); f.writeText(f.readText().take(40))
        val p2 = Proc(lossy = false)
        assertTrue(p2.store.hasSavedSession())
        assertFalse(p2.engine.restoreIfAvailable())      // "nothing to restore", no exception
    }

    @Test fun `a crash after the temp file was written but before the rename still loads the new checkpoint`() {
        val p1 = Proc(lossy = false); p1.engine.loadLeads(leads(3), "t")
        val real = File(dir, "session_v2.json"); val tmp = File(dir, "session_v2.json.tmp")
        real.copyTo(tmp); real.delete()                  // exactly the state DurableFile's fallback can leave
        val p2 = Proc(lossy = false)
        assertTrue(p2.engine.restoreIfAvailable()); assertEquals(3, p2.engine.leads.size)
    }

    @Test fun `journal that cannot be written - the app refuses to dial instead of risking a double call`() {
        val p = Proc(lossy = false); p.engine.loadLeads(leads(3), "t")
        File(dir, "dial_journal.log").mkdirs()           // from now on every append fails (disk full / read-only)
        p.engine.start()
        assertTrue(p.act.dialed.isEmpty())
        assertTrue(p.fake.errors.any { it.contains("double call") })
        assertEquals(CallSequencer.Status.PENDING, p.engine.leads[0].status)
    }

    @Test fun `failed startActivity cancels the write-ahead record so the lead is retried after a restart`() {
        val p1 = Proc(lossy = false); p1.act.failDial = SecurityException("no CALL_PHONE")
        p1.engine.loadLeads(leads(3), "t"); p1.engine.start()
        assertEquals(CallSequencer.Status.PENDING, p1.engine.leads[0].status)
        p1.flushWrites()
        val p2 = Proc(lossy = false); assertTrue(p2.engine.restoreIfAvailable())
        assertEquals("never placed, so it must still be dialable", CallSequencer.Status.PENDING, p2.engine.leads[0].status)
    }

    @Test fun `restore prompt summary shows progress and nothing is dialed automatically`() {
        val p1 = Proc(lossy = false); p1.engine.loadLeads(leads(4), "Diwali list"); p1.engine.start(); p1.call()
        val p2 = Proc(lossy = false); assertTrue(p2.engine.restoreIfAvailable())
        assertEquals("Diwali list - 1 of 4 done", p2.engine.restoreSummary)
        assertTrue(p2.act.dialed.isEmpty())
    }

    /** Crash at random points, many times, with lost checkpoints and torn journal tails. Nobody may be called twice. */
    @Test(timeout = 120_000) fun `fuzz - random operations with random process deaths never produce a double dial`() {
        for (seed in 1..25) {
            allProcs.forEach { it.engine.teardown() }; allProcs.clear()
            dir = java.nio.file.Files.createTempDirectory("ad").toFile(); Handler.queue.clear()
            val r = Random(seed)
            var p = Proc(); p.engine.loadLeads(leads(60), "fuzz"); p.flushWrites()
            var kills = 0
            for (step in 0 until 250) {
                when (r.nextInt(12)) {
                    0, 1 -> p.engine.start()
                    2 -> p.engine.stop()
                    3 -> p.engine.pause()
                    4, 5 -> p.engine.selectOutcome("X")
                    6, 7 -> p.call()
                    8 -> p.act.telephony.emit(OFF).also { Handler.drain() }
                    9 -> p.flushWrites()
                    else -> { // KILL + restart
                        p.kill(); kills++
                        if (r.nextInt(4) == 0) File(dir, "dial_journal.log").appendText("J1|junk|1|D|7|90000")
                        p = Proc(lossy = r.nextBoolean())
                        p.writer.passthrough = true
                        val restored = p.engine.restoreIfAvailable()
                        p.writer.passthrough = false
                        if (!restored) break                            // list finished / nothing to restore
                    }
                }
                Handler.drain()
            }
            val all = allDialed()
            val dupes = all.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue("seed $seed: dialed twice after $kills deaths: $dupes", dupes.isEmpty())
        }
    }

    // ---------- keep-alive wiring (foreground service + wake lock) ----------

    private fun withKeepAlive(body: (FakeHost, FakeLock, Proc) -> Unit) {
        val host = FakeHost(); val lock = FakeLock()
        val p = Proc(lossy = false, keepAlive = KeepAliveController(host, lock))
        body(host, lock, p)
    }

    @Test fun `service and wake lock are on while dialing and off after Stop`() = withKeepAlive { host, lock, p ->
        p.engine.loadLeads(leads(3), "t"); assertFalse(lock.held)
        p.engine.start(); assertTrue(host.running); assertTrue(lock.held)
        p.engine.stop(); assertFalse(host.running); assertFalse(lock.held)
    }
    @Test fun `Pause releases them`() = withKeepAlive { host, lock, p ->
        p.engine.loadLeads(leads(3), "t"); p.engine.start(); p.engine.pause()
        assertFalse(host.running); assertFalse(lock.held)
    }
    @Test fun `finishing the whole list releases them`() = withKeepAlive { host, lock, p ->
        p.engine.loadLeads(leads(3), "t"); p.engine.start()
        repeat(3) { p.call(); p.engine.selectOutcome("X") }
        assertEquals(1, p.fake.complete); assertFalse(host.running); assertFalse(lock.held)
    }
    @Test fun `Activity teardown mid-call releases them - no leak`() = withKeepAlive { host, lock, p ->
        p.engine.loadLeads(leads(3), "t"); p.engine.start(); assertTrue(lock.held)
        p.engine.teardown(); assertFalse(host.running); assertFalse(lock.held)
    }
    @Test fun `a refused dial or missing permission leaves nothing running`() = withKeepAlive { host, lock, p ->
        p.act.telephony.failRegister = SecurityException("x")
        p.engine.loadLeads(leads(3), "t"); p.engine.start()
        assertFalse(host.running); assertFalse(lock.held)
    }
    @Test fun `many start-stop cycles never accumulate a held lock`() = withKeepAlive { host, lock, p ->
        p.engine.loadLeads(leads(50), "t")
        repeat(20) { p.engine.start(); p.engine.stop(); p.engine.start(); p.call(); p.engine.pause() }
        p.engine.stop()
        assertFalse(lock.held); assertFalse(host.running)
    }
}
