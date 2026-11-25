package com.autodialer.app

/**
 * Turns raw phone-state numbers (0 = IDLE, 1 = RINGING, 2 = OFFHOOK - the same values as
 * TelephonyManager.CALL_STATE_*) into clean CONNECTED / ENDED events.
 *
 * Telephony callbacks now arrive on a background executor thread, while Start/Stop happen on the
 * UI thread. This class is the single, locked place where that meets:
 *  - duplicate OFFHOOK / IDLE broadcasts produce ONE event;
 *  - every registration gets a new GENERATION. Events from an older registration (still queued on
 *    the executor, or in flight to the UI thread) are dropped, so a stale IDLE can never end a call
 *    that belongs to a newer session;
 *  - a new registration starts with offHook = false, so a leftover flag can never fake a "call ended".
 */
class CallStateTracker {
    enum class Event { CONNECTED, ENDED }

    companion object {
        const val IDLE = 0
        const val RINGING = 1
        const val OFFHOOK = 2
    }

    private var offHook = false
    private var generation = 0

    /** Called on register(): fresh state, returns the generation the new callback must use. */
    @Synchronized fun newGeneration(): Int {
        offHook = false
        return ++generation
    }

    /** Called on unregister(): everything still in flight from the old registration becomes stale. */
    @Synchronized fun invalidate() {
        offHook = false
        generation++
    }

    @Synchronized fun isCurrent(gen: Int): Boolean = gen == generation

    /** Feed a raw state. Returns the event it produces, or null (duplicate, ringing, or stale generation). */
    @Synchronized fun onState(gen: Int, state: Int): Event? {
        if (gen != generation) return null
        return when (state) {
            OFFHOOK -> if (!offHook) { offHook = true; Event.CONNECTED } else null
            IDLE -> if (offHook) { offHook = false; Event.ENDED } else null
            else -> null
        }
    }
}
