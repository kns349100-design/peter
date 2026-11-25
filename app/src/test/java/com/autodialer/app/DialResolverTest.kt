package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test

class DialResolverTest {
    private fun onSmallStack(kb: Int, body: () -> Unit) {
        var err: Throwable? = null
        val t = Thread(null, { try { body() } catch (e: Throwable) { err = e } }, "small", kb * 1024L)
        t.start(); t.join(30_000)
        assertNull("crashed: $err", err)
    }

    private fun drain(seq: CallSequencer, first: Int?, max: Int, dup: (Int) -> Boolean, skipped: MutableList<Int>): Pair<DialStep, Int> {
        var step = DialResolver.resolve(seq, first, max, dup) { skipped.add(it) }
        var slices = 1
        while (step is DialStep.Yield) { step = DialResolver.resolve(seq, step.index, max, dup) { skipped.add(it) }; slices++ }
        return step to slices
    }

    @Test fun `first non-duplicate lead is returned and earlier duplicates are skipped`() {
        val seq = CallSequencer(6); val first = seq.start()
        val skipped = mutableListOf<Int>()
        val (step, _) = drain(seq, first, 500, { it < 3 }, skipped)
        assertEquals(DialStep.Dial(3), step)
        assertEquals(listOf(0, 1, 2), skipped)
        assertEquals(CallSequencer.Phase.DIALING, seq.phase(3))
        assertEquals(CallSequencer.Phase.SKIPPED, seq.phase(1))
    }

    @Test fun `no duplicates means the first lead is dialed with nothing skipped`() {
        val seq = CallSequencer(3); val skipped = mutableListOf<Int>()
        assertEquals(DialStep.Dial(0), DialResolver.resolve(seq, seq.start(), 500, { false }) { skipped.add(it) })
        assertTrue(skipped.isEmpty())
    }

    @Test fun `every lead a duplicate ends Finished with all SKIPPED`() {
        val seq = CallSequencer(10); val skipped = mutableListOf<Int>()
        val (step, _) = drain(seq, seq.start(), 500, { true }, skipped)
        assertEquals(DialStep.Finished, step)
        assertEquals(10, seq.counts()[CallSequencer.Status.SKIPPED])
        assertTrue(seq.isComplete())
    }

    @Test fun `5000 consecutive duplicates on a 128KB stack - no overflow`() = onSmallStack(128) {
        val seq = CallSequencer(5000); val skipped = mutableListOf<Int>()
        // maxSkips huge: the whole chain runs in ONE call, the worst case for stack depth
        val step = DialResolver.resolve(seq, seq.start(), Int.MAX_VALUE, { true }) { skipped.add(it) }
        assertEquals(DialStep.Finished, step); assertEquals(5000, skipped.size)
    }

    @Test fun `500000 consecutive duplicates on a 128KB stack - still constant stack`() = onSmallStack(128) {
        val seq = CallSequencer(500_000); var n = 0
        val step = DialResolver.resolve(seq, seq.start(), Int.MAX_VALUE, { true }) { n++ }
        assertEquals(DialStep.Finished, step); assertEquals(500_000, n)
    }

    @Test fun `time slicing - 5000 duplicates are processed in chunks of at most maxSkips`() {
        val seq = CallSequencer(5000); val skipped = mutableListOf<Int>()
        var step = DialResolver.resolve(seq, seq.start(), 500, { true }) { skipped.add(it) }
        assertTrue(step is DialStep.Yield); assertEquals(500, skipped.size)   // one slice never exceeds the budget
        var slices = 1
        while (step is DialStep.Yield) { step = DialResolver.resolve(seq, step.index, 500, { true }) { skipped.add(it) }; slices++ }
        assertEquals(DialStep.Finished, step); assertEquals(10, slices); assertEquals(5000, skipped.size)
    }

    @Test fun `Stop while a Yield is pending aborts the reserved lead and Start resumes it`() {
        val seq = CallSequencer(1000); val skipped = mutableListOf<Int>()
        val step = DialResolver.resolve(seq, seq.start(), 100, { true }) { skipped.add(it) } as DialStep.Yield
        seq.stop()                                                  // user pressed Stop between slices
        assertEquals(CallSequencer.Phase.ABORTED, seq.phase(step.index))   // reserved, never dialed -> safe
        assertEquals(step.index, seq.continueSequence())            // resumes exactly there
        assertEquals(100, skipped.size)
    }

    @Test fun `a duplicate predicate that is true only for skipped numbers never skips a real lead`() {
        val seq = CallSequencer(8); val dup = setOf(1, 2, 5); val skipped = mutableListOf<Int>()
        var step = DialResolver.resolve(seq, seq.start(), 500, { it in dup }) { skipped.add(it) }
        assertEquals(DialStep.Dial(0), step)
        seq.markDialIssued(0); seq.connectAndEnd()
        step = DialResolver.resolve(seq, seq.advance(), 500, { it in dup }) { skipped.add(it) }
        assertEquals(DialStep.Dial(3), step); assertEquals(listOf(1, 2), skipped)
    }
}
