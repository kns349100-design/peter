package com.autodialer.app

/** What the engine tells the outside world about the dialing session. */
interface SessionKeepAlive {
    /** A dialing session is running (Start pressed). Idempotent. */
    fun onActive(label: String)
    /** Progress while active (notification text) and a heartbeat that renews the wake-lock timeout. */
    fun onProgress(label: String)
    /** The session is not dialing any more (Stop / Pause / finished / Activity gone). Idempotent. */
    fun onInactive()

    companion object {
        val NONE: SessionKeepAlive = object : SessionKeepAlive {
            override fun onActive(label: String) {}
            override fun onProgress(label: String) {}
            override fun onInactive() {}
        }
    }
}

/** Abstraction over PowerManager.WakeLock (non-reference-counted PARTIAL_WAKE_LOCK). */
interface WakeLockLike {
    val isHeld: Boolean
    /** Acquire, or re-acquire to push the timeout out. Must auto-release after [timeoutMs] on its own. */
    fun acquire(timeoutMs: Long)
    fun release()
}

/** Abstraction over the foreground service. [start] may throw (e.g. Android 12+ background-start limits). */
interface ServiceHost {
    fun start(label: String)
    fun update(label: String)
    fun stop()
}

/**
 * Owns the "dialer is alive" resources: foreground service + wake lock. Pure Kotlin so the lifecycle
 * (including "never leaks") is unit-tested on the JVM.
 *
 * Guarantees:
 *  - held <=> active: the wake lock is held only while a session is active, released in a finally block;
 *  - every acquire carries a timeout, so even if a release path were ever missed the CPU is released on
 *    its own after [lockTimeoutMs] without a heartbeat (onProgress renews it on every dial);
 *  - if the service cannot start (OS refuses a foreground start) the session still runs, just without
 *    the service, and [onError] is told - never a crash;
 *  - every method is idempotent and thread-safe.
 */
class KeepAliveController(
    private val host: ServiceHost,
    private val lock: WakeLockLike,
    private val lockTimeoutMs: Long = 10 * 60_000L,
    private val onError: (String) -> Unit = {}
) : SessionKeepAlive {

    private var active = false
    private var serviceUp = false

    @Synchronized override fun onActive(label: String) {
        if (active) { onProgress(label); return }
        active = true
        try {
            host.start(label); serviceUp = true
        } catch (e: RuntimeException) {
            serviceUp = false
            onError("Could not start the background service: ${e.javaClass.simpleName}")
        }
        try { lock.acquire(lockTimeoutMs) } catch (e: RuntimeException) { onError("Could not hold the CPU awake") }
    }

    @Synchronized override fun onProgress(label: String) {
        if (!active) return
        if (serviceUp) try { host.update(label) } catch (e: RuntimeException) { serviceUp = false }
        try { lock.acquire(lockTimeoutMs) } catch (e: RuntimeException) { }
    }

    @Synchronized override fun onInactive() {
        active = false
        try {
            // Always ask the host to stop, even if we believe it is already down: stopService() on a service
            // that is not running is a no-op, and our belief can differ from reality (e.g. after onHostGone()).
            host.stop()
        } catch (e: RuntimeException) {
            // already gone
        } finally {
            serviceUp = false
            try { if (lock.isHeld) lock.release() } catch (e: RuntimeException) { }
        }
    }

    /** The OS destroyed / the user swiped away the service: drop everything that depended on it. */
    @Synchronized fun onHostGone() {
        active = false
        serviceUp = false
        try { if (lock.isHeld) lock.release() } catch (e: RuntimeException) { }
    }

    @Synchronized fun isActive(): Boolean = active
}
