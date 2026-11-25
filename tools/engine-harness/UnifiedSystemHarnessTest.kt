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

/**
 * Capstone: every module at once - CallEngine + CallSequencer state machine + DialResolver slices + CallStateMonitor
 * (background executor, generations) + KeepAliveController (service + wake lock) + SessionStore/DialJournal
 * (atomic checkpoints, write-ahead journal, RecoveryReconciler) + AES-GCM on disk - under random process deaths.
 */
class UnifiedSystemHarnessTest {
    private lateinit var dir: File
    private val procs = mutableListOf<P>()
    private val N = 1200
    private val DUP = 200 until 900                       // 700 consecutive "already called today" numbers -> sliced skip bursts
    private fun num(i: Int) = "9%09d".format(i + 400_000_000)

    inner class P(val lossy: Boolean) {
        val act = Activity(); val writer = ManualExecutor()
        val host = FakeHost(); val lock = FakeLock()
        val store = SessionStore(Context(dir), if (lossy) writer else Executor { it.run() }, dir, HarnessCipher.shared)
        val log = CallLogStore().also { l -> DUP.forEach { l.preload(num(it)) } }     // the (persisted) call log knows these numbers
        val fake = EngineHarnessTest.Fake()
        val engine = CallEngine(act, store, log, fake, KeepAliveController(host, lock))
        init { procs.add(this) }
        fun call() { act.telephony.emit(TelephonyManager.CALL_STATE_OFFHOOK); act.telephony.emit(TelephonyManager.CALL_STATE_IDLE); Handler.drain() }
        fun kill() { writer.lose(); engine.teardown() }
        /** The engine empties its list when the whole session is finished, so "done" = completion was signalled. */
        fun done() = fake.complete > 0 || (engine.leads.isNotEmpty() && engine.leads.all { it.status == CallSequencer.Status.COMPLETED || it.status == CallSequencer.Status.SKIPPED })
    }

    @Before fun setUp() { Handler.queue.clear(); Build.VERSION.SDK_INT = 31; dir = java.nio.file.Files.createTempDirectory("uni").toFile() }
    @After fun tearDown() { procs.forEach { it.engine.teardown() } }

    @Test(timeout = 180_000) fun `1200 leads, 700 duplicates, random process deaths, background telephony events - everything holds together`() {
        for (seed in 1..8) {
            procs.forEach { it.engine.teardown() }; procs.clear(); Handler.queue.clear()
            dir = java.nio.file.Files.createTempDirectory("uni").toFile()
            val r = Random(seed)
            var p = P(lossy = true)
            p.engine.loadLeads(List(N) { Lead("Name$it", num(it)) }, "capstone"); p.writer.runAll()
            var kills = 0

            // ---- chaos phase ----
            for (step in 0 until 600) {
                when (r.nextInt(14)) {
                    0, 1 -> p.engine.start()
                    2 -> p.engine.stop()
                    3 -> p.engine.pause()
                    4, 5 -> p.engine.selectOutcome("X")
                    6, 7, 8 -> p.call()
                    9 -> p.act.telephony.emit(TelephonyManager.CALL_STATE_OFFHOOK).also { Handler.drain() }
                    10 -> p.writer.runAll()
                    else -> {
                        p.kill(); kills++
                        p = P(lossy = r.nextBoolean())
                        p.writer.passthrough = true; val ok = p.engine.restoreIfAvailable(); p.writer.passthrough = false
                        if (!ok) { assertTrue("seed $seed: not restorable but the session never completed", procs.any { it.fake.complete > 0 }); break }
                    }
                }
                Handler.drain()
            }

            // ---- calm drain: a user who just keeps pressing Start and tagging outcomes ----
            var guard = 0
            if (p.engine.leads.isEmpty() && procs.none { it.fake.complete > 0 }) { p.writer.passthrough = true; p.engine.restoreIfAvailable(); p.writer.passthrough = false }
            while (procs.none { it.done() } && guard++ < 20_000) { p.engine.start(); p.call(); p.engine.selectOutcome("X"); Handler.drain() }
            assertTrue("seed $seed: STUCK after drain: " + p.engine.leads.groupingBy { it.status }.eachCount(), procs.any { it.done() })

            // ---- invariants ----
            val dialed = procs.flatMap { it.act.dialed.toList() }
            val dupes = dialed.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue("seed $seed: dialed twice after $kills deaths: $dupes", dupes.isEmpty())
            assertTrue("seed $seed: an already-called number was dialed", dialed.none { d -> DUP.any { num(it) == d } })
            assertTrue("seed $seed: chaos was vacuous (${dialed.size} calls)", dialed.size > 20)
            assertEquals("seed $seed: every non-duplicate lead was called exactly once", N - DUP.count(), dialed.size)
            assertFalse("seed $seed: wake lock leaked", p.lock.held); assertFalse("seed $seed: service leaked", p.host.running)
            val disk = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertFalse("seed $seed: a phone number is readable on disk", (0 until N step 37).any { disk.contains(num(it)) })
            assertFalse("seed $seed: a name is readable on disk", disk.contains("Name1"))
        }
    }

    @Test fun `5000 consecutive skipped leads are resolved in slices without blocking and without stack growth`() {
        val n = 5000; val log = CallLogStore().also { l -> repeat(n) { l.preload(num(it)) } }
        val act = Activity(); val host = FakeHost(); val lock = FakeLock()
        val e = CallEngine(act, SessionStore(Context(dir), Executor { it.run() }, dir, HarnessCipher.shared), log, EngineHarnessTest.Fake(), KeepAliveController(host, lock))
        e.loadLeads(List(n) { Lead("L$it", num(it)) }, "dups")
        var err: Throwable? = null; var slices = 0
        val t = Thread(null, { try { Handler.posts.set(0); e.start(); while (Handler.drain() > 0) { }; slices = 1 + Handler.posts.get() } catch (x: Throwable) { err = x } }, "tiny", 128 * 1024L)
        t.start(); t.join(60_000)
        assertNull("crashed on a 128KB stack: $err", err)
        assertTrue(e.leads.all { it.status == CallSequencer.Status.SKIPPED }); assertTrue(act.dialed.isEmpty())
        assertTrue("sliced into 500-lead chunks ($slices)", slices in 9..12)
        assertFalse(lock.held); assertFalse(host.running); e.teardown()
    }
}
