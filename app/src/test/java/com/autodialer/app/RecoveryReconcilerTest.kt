package com.autodialer.app

import com.autodialer.app.CallSequencer.Status
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RecoveryReconcilerTest {
    private fun session(vararg st: Status, pending: Int = -1) = SavedSession(
        "t", st.mapIndexed { i, s -> Lead("L$i", "9%09d".format(i)).also { it.status = s } }, 0, 2, pendingOutcomeIndex = pending, sessionId = "s")
    private fun rec(t: JournalType, i: Int) = JournalRecord(t, i, "9%09d".format(i))

    @Test fun `dialed lead that the stale checkpoint still shows as PENDING becomes COMPLETED`() {
        val r = RecoveryReconciler.reconcile(session(Status.COMPLETED, Status.PENDING, Status.PENDING), listOf(rec(JournalType.DIAL, 1)))
        assertEquals(Status.COMPLETED, r.session.leads[1].status); assertEquals(listOf(1), r.recoveredDialed)
        assertEquals(1, r.session.pendingOutcomeIndex)          // its outcome is asked for, it is not re-dialed
    }
    @Test fun `CALLING in the checkpoint plus a DIAL record is COMPLETED`() {
        val r = RecoveryReconciler.reconcile(session(Status.CALLING, Status.PENDING), listOf(rec(JournalType.DIAL, 0)))
        assertEquals(Status.COMPLETED, r.session.leads[0].status)
    }
    @Test fun `aborted dial is put back to PENDING`() {
        val r = RecoveryReconciler.reconcile(session(Status.CALLING, Status.PENDING), listOf(rec(JournalType.DIAL, 0), rec(JournalType.ABORT, 0)))
        assertEquals(Status.PENDING, r.session.leads[0].status); assertEquals(listOf(0), r.reverted)
        assertEquals(-1, r.session.pendingOutcomeIndex)
    }
    @Test fun `a manual skip lost with the stale checkpoint is restored`() {
        val r = RecoveryReconciler.reconcile(session(Status.PENDING, Status.PENDING), listOf(rec(JournalType.SKIP, 1)))
        assertEquals(Status.SKIPPED, r.session.leads[1].status)
    }
    @Test fun `records for a different phone at the same index are ignored`() {
        val r = RecoveryReconciler.reconcile(session(Status.PENDING), listOf(JournalRecord(JournalType.DIAL, 0, "9999999999")))
        assertEquals(Status.PENDING, r.session.leads[0].status)
    }
    @Test fun `out of range index is ignored, an existing outcome prompt is kept, finished leads untouched`() {
        val r = RecoveryReconciler.reconcile(session(Status.COMPLETED, Status.SKIPPED, Status.PENDING, pending = 0),
            listOf(rec(JournalType.DIAL, 1), JournalRecord(JournalType.DIAL, 77, "x")))
        assertEquals(Status.SKIPPED, r.session.leads[1].status); assertEquals(0, r.session.pendingOutcomeIndex)
    }
    @Test fun `already tagged lead does not get a second outcome prompt`() {
        val s = session(Status.COMPLETED, Status.PENDING); s.leads[0].outcome = "INTERESTED"
        assertEquals(-1, RecoveryReconciler.reconcile(s, listOf(rec(JournalType.DIAL, 0))).session.pendingOutcomeIndex)
    }

    /** Model of the engine: dial/skip are journaled, the checkpoint lags behind. Crash anywhere. */
    private fun simulate(seed: Int, useJournal: Boolean): Int {
        val r = Random(seed); val n = 40
        val live = MutableList(n) { Status.PENDING }
        var snapshot = live.toList(); val journal = ArrayList<JournalRecord>()
        val everDialed = HashSet<Int>(); val everSkipped = HashSet<Int>()
        for (step in 0 until 80) {
            when (r.nextInt(5)) {
                0, 1 -> live.indexOfFirst { it == Status.PENDING }.takeIf { it >= 0 }?.let { i ->
                    if (useJournal) journal.add(rec(JournalType.DIAL, i)); live[i] = Status.CALLING; everDialed.add(i)
                    if (r.nextInt(8) == 0) { if (useJournal) journal.add(rec(JournalType.ABORT, i)); live[i] = Status.PENDING; everDialed.remove(i) }
                }
                2 -> live.indexOfFirst { it == Status.CALLING }.takeIf { it >= 0 }?.let { live[it] = Status.COMPLETED }
                3 -> live.indexOfFirst { it == Status.PENDING }.takeIf { it >= 0 }?.let { i ->
                    if (useJournal) journal.add(rec(JournalType.SKIP, i)); live[i] = Status.SKIPPED; everSkipped.add(i) }
                else -> snapshot = live.toList()                 // background checkpoint happens now
            }
        }
        // process dies here: only `snapshot` and `journal` exist
        val saved = SavedSession("t", snapshot.mapIndexed { i, s -> Lead("L$i", "9%09d".format(i)).also { it.status = s } }, 0, 2, sessionId = "s")
        val rec = RecoveryReconciler.reconcile(saved, journal).session
        val engineView = rec.leads.map { if (it.status == Status.CALLING) Status.COMPLETED else it.status }   // what restoreIfAvailable does
        return (everDialed.filter { engineView[it] == Status.PENDING } + everSkipped.filter { engineView[it] == Status.PENDING }).size
    }

    @Test fun `crash at any point - no dialed or skipped lead is ever PENDING again (5000 random histories)`() {
        var bad = 0; for (seed in 1..5000) bad += simulate(seed, useJournal = true)
        assertEquals(0, bad)
    }
    @Test fun `CONTROL - without the journal the same histories DO lose track of dialed leads`() {
        var bad = 0; for (seed in 1..5000) bad += simulate(seed, useJournal = false)
        assertTrue("the test would not have caught the bug ($bad)", bad > 1000)
    }
}
