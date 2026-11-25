package com.autodialer.app

import com.autodialer.app.CallStateTracker.Event
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CallStateTrackerTest {
    private val IDLE = CallStateTracker.IDLE; private val RING = CallStateTracker.RINGING; private val OFF = CallStateTracker.OFFHOOK

    @Test fun `OFFHOOK then IDLE gives CONNECTED then ENDED`() {
        val t = CallStateTracker(); val g = t.newGeneration()
        assertEquals(Event.CONNECTED, t.onState(g, OFF)); assertEquals(Event.ENDED, t.onState(g, IDLE))
    }
    @Test fun `duplicates and ringing produce nothing`() {
        val t = CallStateTracker(); val g = t.newGeneration()
        assertNull(t.onState(g, IDLE)); assertNull(t.onState(g, RING))        // initial IDLE on registration
        assertEquals(Event.CONNECTED, t.onState(g, OFF)); assertNull(t.onState(g, OFF))
        assertEquals(Event.ENDED, t.onState(g, IDLE)); assertNull(t.onState(g, IDLE))
    }
    @Test fun `stale generation after unregister is dropped`() {
        val t = CallStateTracker(); val g = t.newGeneration()
        t.onState(g, OFF); t.invalidate()
        assertFalse(t.isCurrent(g)); assertNull(t.onState(g, IDLE))
    }
    @Test fun `leftover offHook flag cannot fake a call end after re-register`() {
        val t = CallStateTracker(); val g1 = t.newGeneration()
        t.onState(g1, OFF)                        // answered, then Stop (no IDLE seen)
        t.invalidate()
        val g2 = t.newGeneration()                // Start again
        assertNull("the registration-time IDLE must not end anything", t.onState(g2, IDLE))
    }
    @Test fun `RINGING does not count as connected`() {
        val t = CallStateTracker(); val g = t.newGeneration()
        assertNull(t.onState(g, RING)); assertNull(t.onState(g, IDLE))
    }

    @Test(timeout = 30_000) fun `no event of an invalidated generation is ever produced after invalidate returns`() {
        repeat(300) {
            val t = CallStateTracker(); val g = t.newGeneration()
            val invalidated = AtomicBoolean(false); val leaked = AtomicInteger()
            val feeder = Thread { var s = true; while (!Thread.currentThread().isInterrupted) {
                val after = invalidated.get()                        // read BEFORE the call
                val e = t.onState(g, if (s) OFF else IDLE); s = !s
                if (after && e != null) leaked.incrementAndGet()
            } }
            feeder.start(); Thread.sleep(1); t.invalidate(); invalidated.set(true); Thread.sleep(1)
            feeder.interrupt(); feeder.join()
            assertEquals(0, leaked.get())
        }
    }

    /** Telephony thread -> main-thread queue, with Stop (invalidate) happening in between. */
    @Test(timeout = 30_000) fun `queued events from before Stop are rejected by the UI-thread re-check`() {
        val t = CallStateTracker(); val g = t.newGeneration()
        val telephony = Executors.newSingleThreadExecutor(); val main = Executors.newSingleThreadExecutor()
        val applied = AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        main.submit { gate.await() }                                  // main thread busy handling Stop...
        telephony.submit { t.onState(g, OFF)?.let { main.submit { if (t.isCurrent(g)) applied.incrementAndGet() } } }.get()
        telephony.submit { t.onState(g, IDLE)?.let { main.submit { if (t.isCurrent(g)) applied.incrementAndGet() } } }.get()
        t.invalidate()                                                // ...Stop runs before the queued events do
        gate.countDown()
        main.submit { }.get(); telephony.shutdown(); main.shutdown()
        assertEquals("stale events must be dropped", 0, applied.get())
    }
}
