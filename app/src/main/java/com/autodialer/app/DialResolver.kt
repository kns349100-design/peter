package com.autodialer.app

/** Result of one pass over the list. */
sealed class DialStep {
    /** Place a call to [index]. The sequencer already reserved it (DIALING). */
    data class Dial(val index: Int) : DialStep()

    /** Time-slice used up. [index] is already reserved - call resolve() again from it (after letting the UI breathe). */
    data class Yield(val index: Int) : DialStep()

    /** Nothing dialable is left. */
    object Finished : DialStep()
}

/**
 * Finds the next lead that really has to be dialed, skipping duplicates in a plain loop.
 *
 * This replaces the old dial() -> autoSkip -> dial() recursion, whose stack depth grew by one
 * level (two frames) per consecutive skipped lead. Here the stack never grows, however many
 * leads are skipped, and [maxSkips] bounds how long one call may occupy the main thread, so a
 * list full of duplicates can never freeze the UI long enough for an ANR.
 *
 * Pure Kotlin (no Android) so it is unit tested on the JVM.
 */
object DialResolver {
    fun resolve(
        seq: CallSequencer,
        first: Int?,
        maxSkips: Int,
        isDuplicate: (Int) -> Boolean,
        onSkipped: (Int) -> Unit
    ): DialStep {
        var index = first
        var skipped = 0
        while (index != null) {
            if (!isDuplicate(index)) return DialStep.Dial(index)
            onSkipped(index)
            skipped++
            index = seq.autoSkipCurrentAndAdvance()
            if (index != null && skipped >= maxSkips) return DialStep.Yield(index)
        }
        return DialStep.Finished
    }
}
