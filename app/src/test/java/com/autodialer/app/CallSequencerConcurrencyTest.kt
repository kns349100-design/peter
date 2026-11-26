package com.autodialer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.random.Random

class CallSequencerConcurrencyTest {

    // ---------- deterministic reproductions of the Round-2 bugs ----------

    @Test fun `STOP-FIX REDIAL - connected call is never re-dialed after Stop and Start`() {
        val seq = CallSequencer(3)
        assertEquals(0, seq.start())
        seq.markDialIssued(0)
        seq.onCallConnected()                 // lead picked up
        seq.stop()                            // user panics
        assertEquals(CallSequencer.Phase.COMPLETED, seq.phase(0))
        assertEquals(0, seq.takeInterrupted())  // engine will ask for an outcome instead
        assertEquals(1, seq.continueSequence()) // moves on, lead 0 is NEVER granted again
        assertEquals(CallSequencer.Phase.COMPLETED, seq.phase(0))
    }

    @Test fun `placed but not yet answered call is also never re-dialed`() {
        val seq = CallSequencer(2)
        seq.start(); seq.markDialIssued(0)    // ringing, OFFHOOK not seen yet
        seq.stop()
        assertEquals(CallSequencer.Phase.COMPLETED, seq.phase(0))
        assertEquals(1, seq.continueSequence())
    }

    @Test fun `dial that was never placed is retryable (ABORTED)`() {
        val seq = CallSequencer(2)
        seq.start()                           // reserved, startActivity failed
        assertTrue(seq.abortDial(0))
        assertEquals(CallSequencer.Phase.ABORTED, seq.phase(0))
        assertEquals(0, seq.continueSequence())
    }

    @Test fun `abortDial is refused once the call was placed`() {
        val seq = CallSequencer(2)
        seq.start(); seq.markDialIssued(0)
        assertFalse(seq.abortDial(0))
        assertEquals(CallSequencer.Phase.DIALING, seq.phase(0))
    }

    @Test fun `advance during an active call changes nothing`() {
        val seq = CallSequencer(3)
        seq.start(); seq.markDialIssued(0)
        assertNull(seq.advance())
        assertEquals(0, seq.currentIndex)     // round-1 code drifted to 1 here
        seq.onCallConnected()
        assertTrue(seq.onCallEnded())
        assertEquals(CallSequencer.Phase.COMPLETED, seq.phase(0))
        assertEquals(CallSequencer.Phase.PENDING, seq.phase(1))
    }

    @Test fun `stale IDLE before OFFHOOK cannot complete a freshly dialed lead`() {
        val seq = CallSequencer(2)
        seq.start(); seq.markDialIssued(0)
        assertFalse("IDLE with no OFFHOOK for this call must be ignored", seq.onCallEnded())
        assertEquals(CallSequencer.Phase.DIALING, seq.phase(0))
    }

    @Test fun `duplicate OFFHOOK and IDLE events are harmless`() {
        val seq = CallSequencer(2)
        seq.start(); seq.markDialIssued(0)
        assertTrue(seq.onCallConnected()); assertFalse(seq.onCallConnected())
        assertTrue(seq.onCallEnded()); assertFalse(seq.onCallEnded())
    }

    @Test fun `forceCallingAt is refused while a call is active`() {
        val seq = CallSequencer(3)
        seq.start(); seq.markDialIssued(0)
        assertNull(seq.forceCallingAt(2))
        assertEquals(1, seq.inFlightCount())
    }

    @Test fun `restored CALLING status is treated as placed and never re-dialed`() {
        val seq = CallSequencer(3)
        seq.restoreStatus(0, CallSequencer.Status.CALLING)
        seq.restoreStatus(1, CallSequencer.Status.PENDING)
        assertEquals(1, seq.continueSequence())
    }

    // ---------- real multi-threaded tests ----------

    @Test(timeout = 60_000) fun `two threads racing advance - never two grants`() {
        val barrier = CyclicBarrier(3)
        val rounds = 200_000
        val holder = java.util.concurrent.atomic.AtomicReference(CallSequencer(3))
        val res = arrayOfNulls<Int>(2)
        val doubles = AtomicInteger()
        val ts = (0..1).map { id -> Thread { repeat(rounds) { barrier.await(); res[id] = holder.get().advance(); barrier.await() } }.also { it.start() } }
        repeat(rounds) {
            holder.set(CallSequencer(3)); res[0] = null; res[1] = null
            barrier.await(); barrier.await()
            if (res[0] != null && res[1] != null) doubles.incrementAndGet()
            assertEquals(1, holder.get().inFlightCount())
        }
        ts.forEach { it.join() }
        assertEquals(0, doubles.get())
    }

    /**
     * 1000 leads. A "panic" UI thread hammers Start/Continue/Pause/Resume/Stop/Skip, a telephony
     * thread connects & ends calls as fast as it can, a timer thread keeps calling advance(), some
     * dials fail ("network drop"). Invariants: a lead's call is placed at most once, never more
     * than one call in flight, no deadlock, nothing lost.
     */
    @Test(timeout = 120_000) fun `panic Stop-Resume storm on 1000 leads keeps every invariant`() {
        val n = 1000
        val seq = CallSequencer(n)
        val placed = AtomicIntegerArray(n)
        val doubleDial = AtomicInteger()
        val twoInFlight = AtomicInteger()
        val running = AtomicBoolean(true)

        // what CallEngine.dial() does with an index it was granted
        fun dispatch(idx: Int?) {
            if (idx == null) return
            if (placed.get(idx) > 0) doubleDial.incrementAndGet()   // granted a lead that was already called
            if (Random.nextInt(10) == 0) seq.abortDial(idx)          // network drop: nothing placed
            else if (seq.markDialIssued(idx)) placed.incrementAndGet(idx)
        }

        val ui = Thread {
            val r = Random(1)
            while (running.get()) when (r.nextInt(6)) {
                0 -> dispatch(seq.start())
                1 -> dispatch(seq.continueSequence())
                2 -> seq.pause()
                3 -> dispatch(seq.resume())
                4 -> seq.stop()
                else -> dispatch(seq.skip())
            }
        }
        val telephony = Thread {
            while (running.get()) {
                seq.onCallConnected()                       // OFFHOOK
                if (seq.onCallEnded()) dispatch(seq.advance()) // IDLE -> outcome tapped -> next lead
            }
        }
        val timer = Thread { while (running.get()) dispatch(seq.advance()) }
        val sampler = Thread { while (running.get()) if (seq.inFlightCount() > 1) twoInFlight.incrementAndGet() }

        val all = listOf(ui, telephony, timer, sampler)
        all.forEach { it.start() }
        Thread.sleep(3000)
        running.set(false)
        all.forEach { it.join(10_000); assertFalse("deadlock / stuck thread", it.isAlive) }

        // drain: finish whatever is left, single-threaded, like a calm user pressing Start repeatedly
        calmDrain(seq, n, placed, doubleDial)
        val totalPlaced = (0 until n).sumOf { placed.get(it) }
        assertTrue("storm was vacuous: only $totalPlaced calls placed", totalPlaced > 100)

        assertEquals("a lead was granted again after its call had been placed", 0, doubleDial.get())
        assertEquals("more than one call in flight at the same time", 0, twoInFlight.get())
        for (i in 0 until n) assertTrue("lead $i placed ${placed.get(i)} times", placed.get(i) <= 1)
        val c = seq.counts()
        assertEquals(n, c.values.sum())
        assertEquals("a lead was lost/stuck", 0, (c[CallSequencer.Status.PENDING] ?: 0) + (c[CallSequencer.Status.CALLING] ?: 0))
        assertEquals(0, seq.inFlightCount())
    }

    /**
     * What a calm user does after the chaos: the phone call that was still in flight when everything stopped
     * ends (OFFHOOK -> IDLE), THEN Start is pressed until nothing is left. Skipping the first step is a test
     * bug, not a sequencer bug: continueSequence() correctly refuses to start a second call while one is active
     * (on a slow 2-core CI machine the storm threads often stop in the middle of a call).
     */
    private fun calmDrain(seq: CallSequencer, n: Int, placed: AtomicIntegerArray, doubleDial: AtomicInteger) {
        var guard = 0
        while (guard++ < 10 * n) {
            seq.onCallConnected(); seq.onCallEnded()           // the in-flight call finishes
            val idx = seq.continueSequence() ?: break            // null only when nothing is left to dial
            if (placed.get(idx) > 0) doubleDial.incrementAndGet()
            if (seq.markDialIssued(idx)) placed.incrementAndGet(idx)
        }
    }

    @Test fun `REGRESSION (CI failure) - a call still in flight when the storm stops must not leave leads stuck after a calm drain`() {
        val n = 50; val seq = CallSequencer(n); val placed = AtomicIntegerArray(n); val dd = AtomicInteger()
        val first = seq.start()!!; assertTrue(seq.markDialIssued(first)); placed.incrementAndGet(first)   // storm stopped mid-call
        calmDrain(seq, n, placed, dd)
        assertEquals(0, dd.get())
        assertEquals(n, seq.counts()[CallSequencer.Status.COMPLETED]); assertEquals(0, seq.inFlightCount())
        for (i in 0 until n) assertEquals("lead $i", 1, placed.get(i))
    }

    @Test fun `a reservation can be confirmed only once - a stale second grant holder loses`() {
        val seq = CallSequencer(3)
        val a = seq.start()!!                          // thread A is granted lead 0
        seq.stop()                                     // aborted before it was placed
        val b = seq.continueSequence()!!               // thread B is granted lead 0 again
        assertEquals(a, b)
        assertTrue("first confirmation wins", seq.markDialIssued(a))
        assertFalse("the other holder of the same index must NOT also dial it", seq.markDialIssued(b))
    }

    @Test fun `CONTROL - continueSequence correctly refuses while a call is active (why the old drain stopped early)`() {
        val seq = CallSequencer(5); val first = seq.start()!!; seq.markDialIssued(first)
        assertNull("a second call must never start while one is active", seq.continueSequence())
        assertEquals(1, seq.counts()[CallSequencer.Status.CALLING])
    }

    @Test(timeout = 60_000) fun `1 second calls on 1000 leads complete every lead exactly once`() {
        val n = 1000
        val seq = CallSequencer(n)
        val placed = IntArray(n)
        var idx = seq.start()
        while (idx != null) {
            placed[idx]++
            seq.markDialIssued(idx)
            seq.onCallConnected(); assertTrue(seq.onCallEnded())
            assertFalse(seq.onCallEnded())          // duplicate IDLE
            idx = seq.advance()
        }
        assertTrue(placed.all { it == 1 })
        assertEquals(n, seq.counts()[CallSequencer.Status.COMPLETED])
        assertTrue(seq.isComplete())
    }
}
