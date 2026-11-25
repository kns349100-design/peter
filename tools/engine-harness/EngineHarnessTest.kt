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
import kotlin.random.Random

/** Runs the real CallEngine on the JVM: real sequencer, real monitor/tracker, real executor thread. */
class EngineHarnessTest {
    class Fake : CallEngineListener {
        @Volatile var errors = mutableListOf<String>(); var awaiting = mutableListOf<Int>(); var complete = 0
        override fun onLeadUpdated(index: Int, status: CallSequencer.Status) {}
        override fun onDialing(index: Int, lead: Lead) {}
        override fun onCallEndedAwaitingOutcome(index: Int) { awaiting.add(index) }
        override fun onBatchComplete(callsDone: Int) {}
        override fun onSessionPaused() {}
        override fun onSessionResumed() {}
        override fun onSessionStopped() {}
        override fun onSessionComplete() { complete++ }
        override fun onEngineError(message: String) { errors.add(message) }
        override fun onLog(message: String) {}
    }

    private lateinit var act: Activity
    private lateinit var log: CallLogStore
    private lateinit var fake: Fake
    private lateinit var engine: CallEngine
    private fun num(i: Int) = "9%09d".format(i + 100_000_000)
    private fun leads(n: Int) = List(n) { Lead("L$it", num(it)) }

    @Before fun setUp() {
        Handler.queue.clear(); Build.VERSION.SDK_INT = 31
        act = Activity(); log = CallLogStore(); fake = Fake()
        val dir = java.nio.file.Files.createTempDirectory("ad").toFile()
        engine = CallEngine(act, SessionStore(Context(dir), java.util.concurrent.Executor { it.run() }, dir, HarnessCipher.shared), log, fake)
    }
    @After fun tearDown() { engine.teardown() }

    private val OFFHOOK = TelephonyManager.CALL_STATE_OFFHOOK
    private val IDLE = TelephonyManager.CALL_STATE_IDLE
    /** What a real call looks like: OFFHOOK then IDLE arrive on the telephony thread, then the UI thread runs them. */
    private fun fullCall() { act.telephony.emit(OFFHOOK); act.telephony.emit(IDLE); Handler.drain() }

    // ---------- 1. recursion -> loop ----------

    private fun runOnSmallStack(kb: Int, body: () -> Unit) {
        var err: Throwable? = null
        val t = Thread(null, { try { body() } catch (e: Throwable) { err = e } }, "small-stack", kb * 1024L)
        t.start(); t.join(60_000)
        assertNull("crashed: $err", err)
    }

    private fun skipAll(n: Int): Int {
        log.preload(*Array(n) { num(it) })          // every number already called today -> every lead is a duplicate
        engine.loadLeads(leads(n), "dups")
        Handler.posts.set(0)
        engine.start()
        while (Handler.drain() > 0) { }
        return 1 + Handler.posts.get()      // first slice + one posted continuation per further slice
    }

    @Test fun `5000 consecutive duplicates on a 256KB stack - no overflow, all skipped, UI sliced`() {
        var slices = 0
        runOnSmallStack(256) { slices = skipAll(5000) }
        assertTrue(engine.leads.all { it.status == CallSequencer.Status.SKIPPED })
        assertTrue("nothing may be dialed", act.dialed.isEmpty())
        assertTrue("work was sliced into ~500-lead chunks (got $slices)", slices in 9..12)
        assertTrue(fake.complete >= 1)
    }

    @Test fun `50000 consecutive duplicates on a 256KB stack`() {
        runOnSmallStack(256) { skipAll(50_000) }
        assertTrue(engine.leads.all { it.status == CallSequencer.Status.SKIPPED })
    }

    @Test fun `Stop during a skip burst halts it and Start finishes it`() {
        val n = 5000
        log.preload(*Array(n) { num(it) }); engine.loadLeads(leads(n), "dups"); engine.start()
        engine.stop()                      // clears the queued continuation
        assertEquals(0, Handler.drain())
        val skippedAfterStop = engine.leads.count { it.status == CallSequencer.Status.SKIPPED }
        assertEquals(500, skippedAfterStop)
        engine.start(); while (Handler.drain() > 0) { }
        assertTrue(engine.leads.all { it.status == CallSequencer.Status.SKIPPED })
    }

    @Test fun `duplicates in the middle are skipped and the next real number is dialed`() {
        log.preload(num(0), num(1), num(2))
        engine.loadLeads(leads(5), "mix"); engine.start()
        assertEquals(listOf(num(3)), act.dialed)
        assertEquals(3, engine.leads.count { it.status == CallSequencer.Status.SKIPPED })
    }

    // ---------- 2. telephony callbacks on a background thread ----------

    @Test fun `full cycle - callbacks come from the call-state thread and every lead completes once`() {
        engine.loadLeads(leads(50), "t"); engine.start()
        for (i in 0 until 50) {
            assertEquals(i + 1, act.dialed.size)
            fullCall()
            assertEquals(i, fake.awaiting.last())
            engine.selectOutcome("INTERESTED")
        }
        assertEquals((0 until 50).map { num(it) }, act.dialed)
        assertTrue(engine.leads.all { it.status == CallSequencer.Status.COMPLETED })
    }

    @Test fun `spurious duplicate IDLE cannot end the next call`() {
        engine.loadLeads(leads(3), "t"); engine.start()
        fullCall(); engine.selectOutcome("X")              // lead 1 now dialing
        act.telephony.emit(IDLE); Handler.drain()           // stray IDLE before lead 1 even connected
        assertEquals(CallSequencer.Status.CALLING, engine.leads[1].status)
        assertEquals(2, act.dialed.size)
    }

    @Test fun `STOP-FIX - answered call is never re-dialed after Stop and Start`() {
        engine.loadLeads(leads(3), "t"); engine.start()
        act.telephony.emit(OFFHOOK); Handler.drain()        // lead 0 picked up
        engine.stop()
        assertEquals(CallSequencer.Status.COMPLETED, engine.leads[0].status)
        act.telephony.emit(IDLE); Handler.drain()           // call ends after Stop - listener is gone
        engine.start()                                      // must ask for outcome, not dial
        assertEquals(listOf(0), fake.awaiting)
        assertEquals(listOf(num(0)), act.dialed)
        engine.selectOutcome("X")
        assertEquals("lead 0 must never appear twice", act.dialed.distinct(), act.dialed)
    }

    @Test fun `phone permission missing - clear error and no call is placed`() {
        act.telephony.failRegister = SecurityException("READ_PHONE_STATE")
        engine.loadLeads(leads(2), "t"); engine.start()
        assertTrue(fake.errors.any { it.contains("permission", ignoreCase = true) })
        assertTrue(act.dialed.isEmpty())
        assertEquals(CallSequencer.Status.PENDING, engine.leads[0].status)
    }

    @Test fun `busy phone - lead goes back to pending and can be dialed later`() {
        act.telephony.callState = OFFHOOK
        engine.loadLeads(leads(2), "t"); engine.start()
        assertTrue(fake.errors.isNotEmpty()); assertTrue(act.dialed.isEmpty())
        assertEquals(CallSequencer.Status.PENDING, engine.leads[0].status)
        act.telephony.callState = IDLE
        engine.start()
        assertEquals(listOf(num(0)), act.dialed)
    }

    @Test fun `failed startActivity makes the lead retryable`() {
        act.failDial = SecurityException("CALL_PHONE")
        engine.loadLeads(leads(2), "t"); engine.start()
        assertEquals(CallSequencer.Status.PENDING, engine.leads[0].status)
        act.failDial = null; engine.start()
        assertEquals(listOf(num(0)), act.dialed)
    }

    @Test fun `teardown leaves no call-state thread behind`() {
        engine.loadLeads(leads(2), "t"); engine.start()
        act.telephony.emit(OFFHOOK)         // first callback creates the executor thread
        assertTrue(Thread.getAllStackTraces().keys.any { it.name == "call-state" })
        engine.teardown()
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && Thread.getAllStackTraces().keys.any { it.name == "call-state" && it.isAlive }) Thread.sleep(20)
        assertFalse(Thread.getAllStackTraces().keys.any { it.name == "call-state" && it.isAlive })
    }

    @Test fun `registerTelephonyCallback is used on Android 12 and the legacy path on Android 9`() {
        engine.loadLeads(leads(2), "t"); engine.start()
        assertNotNull("API 31+ must use TelephonyCallback", act.telephony.cb)
        engine.teardown()
        assertNull(act.telephony.cb)
    }

    /** Real threads: a telephony thread spams OFFHOOK/IDLE while the "UI" hammers Start/Stop/Pause/Skip/outcome. */
    @Test(timeout = 60_000) fun `engine-level chaos - UI storm vs telephony thread never double-dials`() {
        val n = 300
        engine.loadLeads(leads(n), "chaos"); 
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val phone = Thread {
            val r = Random(7)
            while (running.get()) act.telephony.emit(if (r.nextBoolean()) OFFHOOK else IDLE)
        }
        phone.start()
        val r = Random(3); val end = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < end) {
            when (r.nextInt(7)) {
                0 -> engine.start(); 1 -> engine.stop(); 2 -> engine.pause(); 3 -> engine.skip()
                4 -> engine.selectOutcome("X"); 5 -> engine.resume(); else -> Handler.drain()
            }
            Handler.drain()
        }
        running.set(false); phone.join(10_000); assertFalse(phone.isAlive)
        val d = act.dialed.toList()
        assertEquals("a number was dialed twice: ${d.groupBy { it }.filter { it.value.size > 1 }.keys}", d.distinct().size, d.size)
        assertTrue("chaos was vacuous (${d.size} calls)", d.size >= 3)
    }
}
