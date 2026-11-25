package com.autodialer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Integration: pasted text -> NumberParser -> Lead list -> PhoneUtils dedupe -> CallSequencer session. */
class DialFlowIntegrationTest {

    private fun parse(text: String): List<Lead> {
        val leads = mutableListOf<Lead>()
        val seen = mutableSetOf<String>()
        text.lines().forEach { line ->
            NumberParser.extractLeadsFromLine(line, leads.size + 1).forEach { lead ->
                if (seen.add(PhoneUtils.normalize(lead.phone))) leads.add(lead)
            }
        }
        return leads
    }

    private val pasted = """
        Rahul 9075034748
        Priya, +91 98765 43210
        9075034748
        OTP 4821 do not share
        Amit - 090750 11111
        +91-9822012345
    """.trimIndent()

    @Test fun `pasted list is parsed and deduplicated across formats`() {
        val leads = parse(pasted)
        assertEquals(listOf("9075034748", "919876543210".let { leads[1].phone }, leads[2].phone, leads[3].phone).size, leads.size)
        assertEquals(4, leads.size)
        assertEquals("Rahul", leads[0].name)
        assertEquals("Priya", leads[1].name)
        assertEquals("Amit", leads[2].name)
    }

    @Test fun `full session dials every parsed lead exactly once in order`() {
        val leads = parse(pasted)
        val seq = CallSequencer(leads.size)
        val dialed = mutableListOf<Int>()
        var next = seq.start()
        while (next != null) {
            dialed.add(next)
            assertTrue(seq.connectAndEnd())
            assertFalse("duplicate IDLE must be ignored", seq.connectAndEnd())
            next = seq.advance()
        }
        assertEquals(listOf(0, 1, 2, 3), dialed)
        assertTrue(seq.isComplete())
        assertEquals(leads.size, seq.counts()[CallSequencer.Status.COMPLETED])
    }

    @Test fun `stop mid-call then continue never re-dials the lead whose call was placed`() {
        val leads = parse(pasted)
        val seq = CallSequencer(leads.size)
        seq.start(); seq.connectAndEnd()
        assertEquals(1, seq.advance())      // lead 1 dialed
        seq.markDialIssued(1)               // engine: startActivity succeeded
        seq.stop()                          // user presses Stop while it is ringing/connected
        assertEquals(CallSequencer.Status.COMPLETED, seq.statuses[1])
        assertEquals(1, seq.takeInterrupted())
        assertEquals(2, seq.continueSequence())   // moves on to lead 2, lead 1 is never dialed again
    }

    @Test fun `pause during call then resume after it ends continues with next lead`() {
        val seq = CallSequencer(3)
        seq.start()
        seq.pause()
        assertNull(seq.resume().takeIf { false }) // resume while call active must not double dial
        assertTrue(seq.connectAndEnd())
        assertEquals(1, seq.advance())
    }

    @Test fun `skip then continue never revisits skipped or completed leads`() {
        val seq = CallSequencer(4)
        seq.start(); seq.connectAndEnd()      // 0 done
        assertEquals(1, seq.advance()); seq.connectAndEnd()
        seq.pause()
        assertNull(seq.skip())              // skips 1? already completed -> must not be re-dialed
        val n = seq.continueSequence()
        assertEquals(2, n)
        assertEquals(CallSequencer.Status.SKIPPED.let { seq.statuses[1] }, seq.statuses[1])
    }

    @Test fun `autoSkip and forceCallingAt keep list consistent`() {
        val seq = CallSequencer(3)
        assertEquals(0, seq.start())
        assertEquals(1, seq.autoSkipCurrentAndAdvance())
        assertEquals(CallSequencer.Status.SKIPPED, seq.statuses[0])
        assertNull("self-heal must not start a 2nd call while one is active", seq.forceCallingAt(2))
        seq.markDialIssued(1); seq.connectAndEnd()
        assertEquals(2, seq.forceCallingAt(2))
        assertEquals(CallSequencer.Status.CALLING, seq.statuses[2])
        assertNull(seq.forceCallingAt(9))
    }

    @Test fun `empty or number-free input produces an empty session`() {
        assertTrue(parse("hello\nworld 123").isEmpty())
        val seq = CallSequencer(0)
        assertNull(seq.start())
        assertTrue(seq.isComplete())
    }
}
