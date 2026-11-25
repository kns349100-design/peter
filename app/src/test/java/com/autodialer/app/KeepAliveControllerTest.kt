package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class KeepAliveControllerTest {
    private class Lock : WakeLockLike { @Volatile var held = false; var lastTimeout = 0L; var acquires = 0
        override val isHeld get() = held
        override fun acquire(timeoutMs: Long) { held = true; lastTimeout = timeoutMs; acquires++ }
        override fun release() { held = false } }
    private class Host : ServiceHost { @Volatile var running = false; var starts = 0; var stops = 0; var updates = 0
        var failStart: RuntimeException? = null; var failStop: RuntimeException? = null
        override fun start(label: String) { failStart?.let { throw it }; running = true; starts++ }
        override fun update(label: String) { updates++ }
        override fun stop() { stops++; running = false; failStop?.let { throw it } } }

    private fun make(timeout: Long = 600_000L, errors: MutableList<String> = mutableListOf()): Triple<KeepAliveController, Host, Lock> {
        val h = Host(); val l = Lock(); return Triple(KeepAliveController(h, l, timeout) { errors.add(it) }, h, l)
    }

    @Test fun `active holds service and lock, inactive releases both`() {
        val (c, h, l) = make(); c.onActive("a"); assertTrue(h.running && l.held && c.isActive())
        c.onInactive(); assertFalse(h.running); assertFalse(l.held); assertFalse(c.isActive())
    }
    @Test fun `onActive twice starts the service only once`() {
        val (c, h, _) = make(); c.onActive("a"); c.onActive("b"); assertEquals(1, h.starts)
    }
    @Test fun `onInactive is idempotent and safe before any start`() {
        val (c, h, l) = make(); c.onInactive(); c.onInactive(); assertFalse(l.held); assertFalse(h.running)
    }
    @Test fun `every acquire carries a timeout so a missed release can never hold the CPU forever`() {
        val (c, _, l) = make(timeout = 123_000L); c.onActive("a"); assertEquals(123_000L, l.lastTimeout)
        c.onProgress("b"); assertEquals(123_000L, l.lastTimeout); assertEquals(2, l.acquires)   // heartbeat renews
    }
    @Test fun `progress when not active does nothing`() {
        val (c, h, l) = make(); c.onProgress("x"); assertFalse(l.held); assertEquals(0, h.updates)
    }
    @Test fun `service that cannot start still lets the session run, reports it, and cleans up`() {
        val errors = mutableListOf<String>(); val (c, h, l) = make(errors = errors)
        h.failStart = IllegalStateException("background start not allowed")
        c.onActive("a"); assertTrue(errors.isNotEmpty()); assertTrue(l.held)
        c.onProgress("b"); assertEquals(0, h.updates)                  // no service, no updates
        c.onInactive(); assertFalse(l.held)
    }
    @Test fun `service stop failure still releases the lock`() {
        val (c, h, l) = make(); c.onActive("a"); h.failStop = RuntimeException("boom")
        c.onInactive(); assertFalse(l.held)
    }
    @Test fun `OS destroying the service releases the lock`() {
        val (c, _, l) = make(); c.onActive("a"); c.onHostGone(); assertFalse(l.held); assertFalse(c.isActive())
    }
    @Test fun `can be restarted after being stopped`() {
        val (c, h, l) = make(); repeat(5) { c.onActive("a"); c.onInactive() }
        assertEquals(5, h.starts); assertFalse(l.held); assertFalse(h.running)
    }
    @Test(timeout = 30_000) fun `thread storm of start-progress-stop ends with nothing held`() {
        val (c, h, l) = make(); val go = CountDownLatch(1)
        val ts = (0 until 8).map { t -> Thread { go.await(); val r = kotlin.random.Random(t)
            repeat(5000) { when (r.nextInt(5)) { 0, 1 -> c.onActive("x"); 2 -> c.onProgress("y"); 3 -> c.onHostGone(); else -> c.onInactive() } } }.also { it.start() } }
        go.countDown(); ts.forEach { it.join() }
        c.onInactive(); assertFalse("lock leaked", l.held); assertFalse("service leaked", h.running)
    }
}
