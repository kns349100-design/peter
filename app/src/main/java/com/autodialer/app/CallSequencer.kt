package com.autodialer.app

/**
 * Thread-safe state machine for sequential dialing. No Android dependencies on purpose, so it is
 * unit tested on the JVM (see CallSequencerTest / CallSequencerConcurrencyTest).
 *
 * THREAD-SAFETY: every public method takes the same lock, so UI-thread actions (Start / Pause /
 * Stop / Skip) and phone-state callbacks (OFFHOOK / IDLE, whichever thread delivers them) are
 * serialized into one single order. No check-then-act sequence can interleave.
 *
 * LIFECYCLE of one lead:
 *
 *   PENDING --tryBeginCall--> DIALING --OFFHOOK--> CONNECTED --IDLE--> COMPLETED
 *      ^                          |
 *      |                          +-- dial never placed (permission / busy / error) --> ABORTED
 *      +------------------------------------------------------------------------------+ (retryable)
 *
 * THE GUARANTEE: once a dial was actually issued (markDialIssued) or the phone went OFFHOOK, the
 * lead can NEVER go back to PENDING/ABORTED. Stop, Pause, state reset, restore - nothing re-dials it.
 * If Stop is pressed while such a call is in flight it becomes COMPLETED ("interrupted") and the
 * engine asks the user for its outcome instead. Only a dial that was never placed is retryable.
 *
 * Note: Android cannot tell "ringing" from "answered" for outgoing calls without extra
 * permissions, so every placed call is treated as possibly answered - the safe direction.
 */
class CallSequencer(val total: Int) {

    /** UI-facing status. Kept unchanged so adapters / SessionStore (enum names are persisted) keep working. */
    enum class Status { PENDING, CALLING, COMPLETED, SKIPPED }

    /** Internal lifecycle. */
    enum class Phase { PENDING, DIALING, CONNECTED, COMPLETED, SKIPPED, ABORTED }

    private val lock = Any()
    private val phases = Array(total) { Phase.PENDING }
    private val issued = BooleanArray(total)

    private var cur = -1
    private var isPaused = false
    private var isStopped = false
    private var active = false
    private var ended = -1
    private var interrupted = -1

    /** Snapshot (a copy) of every lead's status - safe to read from any thread. */
    val statuses: List<Status> get() = synchronized(lock) { phases.map { toStatus(it) } }
    val currentIndex: Int get() = synchronized(lock) { cur }
    val paused: Boolean get() = synchronized(lock) { isPaused }
    val stopped: Boolean get() = synchronized(lock) { isStopped }
    val callActive: Boolean get() = synchronized(lock) { active }

    fun phase(index: Int): Phase? = synchronized(lock) { phases.getOrNull(index) }

    /** Number of leads currently DIALING or CONNECTED. Must never exceed 1. */
    fun inFlightCount(): Int = synchronized(lock) { phases.count { it == Phase.DIALING || it == Phase.CONNECTED } }

    /** Begins the session. Returns the index to dial, or null if nothing to dial. */
    fun start(): Int? = synchronized(lock) {
        isStopped = false
        isPaused = false
        if (active) return null
        cur = nextPendingLocked(-1)
        tryBeginCallLocked()
    }

    /** Engine calls this right after the dial intent was successfully handed to Android. */
    fun markDialIssued(index: Int): Boolean = synchronized(lock) {
        // Exclusive: only the FIRST confirmation of a reservation wins. A second caller holding a stale grant for the
        // same index (e.g. after stop() aborted it and continueSequence() reserved it again) gets false, so one
        // reservation can never be confirmed - and therefore dialed - twice.
        if (!active || index != cur || phases[index] != Phase.DIALING || issued[index]) return false
        issued[index] = true
        true
    }

    /** The dial was never placed (busy line, permission denied, exception). Makes the lead retryable. */
    fun abortDial(index: Int): Boolean = synchronized(lock) {
        if (index !in 0 until total) return false
        if (phases[index] != Phase.DIALING || issued[index]) return false
        phases[index] = Phase.ABORTED
        if (index == cur) active = false
        true
    }

    /** Phone went OFFHOOK for the active call. */
    fun onCallConnected(): Boolean = synchronized(lock) {
        if (!active || cur !in 0 until total || phases[cur] != Phase.DIALING) return false
        phases[cur] = Phase.CONNECTED
        issued[cur] = true // OFFHOOK proves a call exists, whatever the engine had recorded
        true
    }

    /**
     * Phone went IDLE. Returns true exactly once per real call. Only a CONNECTED lead can be
     * completed this way, so a stale / duplicate / spurious IDLE can never complete a lead whose
     * call has not actually started.
     */
    fun onCallEnded(): Boolean = synchronized(lock) {
        if (!active || cur !in 0 until total || phases[cur] != Phase.CONNECTED) return false
        phases[cur] = Phase.COMPLETED
        active = false
        ended = cur
        true
    }

    /** Index completed by the most recent real onCallEnded() (-1 if none). */
    fun lastEndedIndex(): Int = synchronized(lock) { ended }

    /** After stop(): index of the call that was cut off mid-flight and needs an outcome (-1 if none). Clears it. */
    fun takeInterrupted(): Int = synchronized(lock) { val i = interrupted; interrupted = -1; i }

    /** Move to the next number and dial it. Does NOTHING while a call is active (never moves currentIndex). */
    fun advance(): Int? = synchronized(lock) {
        if (isStopped || isPaused || active) return null
        cur = nextPendingLocked(cur)
        tryBeginCallLocked()
    }

    fun pause() = synchronized(lock) { isPaused = true }

    fun resume(): Int? = synchronized(lock) {
        if (!isPaused) return null
        isPaused = false
        if (active) return null
        advanceLocked()
    }

    fun stop() = synchronized(lock) {
        isStopped = true
        isPaused = false
        if (active && cur in 0 until total) {
            if (issued[cur] || phases[cur] == Phase.CONNECTED) {
                // A call exists (maybe answered). It is NEVER put back to PENDING.
                phases[cur] = Phase.COMPLETED
                interrupted = cur
            } else if (phases[cur] == Phase.DIALING) {
                phases[cur] = Phase.ABORTED // reserved but never placed - safe to retry
            }
        }
        active = false
    }

    /** Continue after Pause/Stop from the first lead that is still dialable. Never touches COMPLETED/SKIPPED. */
    fun continueSequence(): Int? = synchronized(lock) {
        if (active) return null
        isStopped = false
        isPaused = false
        cur = nextPendingLocked(-1)
        tryBeginCallLocked()
    }

    /** Skip the current lead if (and only if) it has not been dialed yet. Refused during/after a call. */
    fun skip(): Int? = synchronized(lock) {
        if (active) return null
        if (cur in 0 until total && (phases[cur] == Phase.PENDING || phases[cur] == Phase.ABORTED)) {
            phases[cur] = Phase.SKIPPED
        }
        advanceLocked()
    }

    /** Pre-dial duplicate guard: current lead was reserved but is a duplicate, so skip it and move on. */
    fun autoSkipCurrentAndAdvance(): Int? = synchronized(lock) {
        if (cur in 0 until total && !issued[cur] &&
            (phases[cur] == Phase.DIALING || phases[cur] == Phase.PENDING || phases[cur] == Phase.ABORTED)
        ) {
            phases[cur] = Phase.SKIPPED
            active = false
        }
        if (active || isStopped || isPaused) return null
        cur = nextPendingLocked(cur)
        tryBeginCallLocked()
    }

    /** Self-heal used when the engine's lead list (source of truth) disagrees. Refused while a call is active. */
    fun forceCallingAt(index: Int): Int? = synchronized(lock) {
        if (index !in 0 until total || active) return null
        isStopped = false
        isPaused = false
        cur = index
        issued[index] = false
        phases[index] = Phase.DIALING
        active = true
        index
    }

    /** Restore a persisted status. A persisted CALLING is treated as COMPLETED: a call was placed, never re-dial it. */
    fun restoreStatus(index: Int, status: Status) = synchronized(lock) {
        if (index !in 0 until total) return@synchronized
        phases[index] = when (status) {
            Status.PENDING -> Phase.PENDING
            Status.CALLING, Status.COMPLETED -> Phase.COMPLETED
            Status.SKIPPED -> Phase.SKIPPED
        }
    }

    fun nextPending(from: Int): Int = synchronized(lock) { nextPendingLocked(from) }

    fun isComplete(): Boolean = synchronized(lock) { cur == -1 && !active }

    fun counts(): Map<Status, Int> = synchronized(lock) {
        Status.values().associateWith { s -> phases.count { toStatus(it) == s } }
    }

    // ---- private, lock must be held ----

    private fun advanceLocked(): Int? {
        if (isStopped || isPaused || active) return null
        cur = nextPendingLocked(cur)
        return tryBeginCallLocked()
    }

    private fun tryBeginCallLocked(): Int? {
        if (isStopped || isPaused || active || cur == -1) return null
        active = true
        issued[cur] = false
        phases[cur] = Phase.DIALING
        return cur
    }

    private fun nextPendingLocked(from: Int): Int {
        for (i in (from + 1) until total) {
            if (phases[i] == Phase.PENDING || phases[i] == Phase.ABORTED) return i
        }
        return -1
    }

    private fun toStatus(p: Phase): Status = when (p) {
        Phase.PENDING, Phase.ABORTED -> Status.PENDING
        Phase.DIALING, Phase.CONNECTED -> Status.CALLING
        Phase.COMPLETED -> Status.COMPLETED
        Phase.SKIPPED -> Status.SKIPPED
    }
}
