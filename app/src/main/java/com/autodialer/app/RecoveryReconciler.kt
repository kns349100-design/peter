package com.autodialer.app

/**
 * Merges the last checkpoint (which may be stale - it is written in the background) with the durable
 * journal (which is never stale for dials and skips). The rules only ever move a lead in the SAFE
 * direction: a number that might have been called is never offered for dialing again.
 */
object RecoveryReconciler {

    data class Result(
        val session: SavedSession,
        val recoveredDialed: List<Int>,
        val recoveredSkipped: List<Int>,
        val reverted: List<Int>
    )

    fun reconcile(saved: SavedSession, records: List<JournalRecord>): Result {
        val leads = saved.leads.map { Lead(it.name, it.phone, it.status, it.outcome) }
        // Last valid record per lead (records for a different number at that index belong to another list).
        val last = HashMap<Int, JournalRecord>()
        var lastDialIndex = -1
        for (r in records) {
            if (r.index !in leads.indices || leads[r.index].phone != r.phone) continue
            last[r.index] = r
            if (r.type == JournalType.DIAL) lastDialIndex = r.index
        }
        val dialed = ArrayList<Int>(); val skipped = ArrayList<Int>(); val reverted = ArrayList<Int>()
        for ((i, r) in last) {
            val lead = leads[i]
            when (r.type) {
                JournalType.DIAL -> if (lead.status == CallSequencer.Status.PENDING || lead.status == CallSequencer.Status.CALLING) {
                    lead.status = CallSequencer.Status.COMPLETED; dialed.add(i)
                }
                JournalType.ABORT -> if (lead.status == CallSequencer.Status.CALLING) {
                    lead.status = CallSequencer.Status.PENDING; reverted.add(i)     // the call never happened
                }
                JournalType.SKIP -> if (lead.status == CallSequencer.Status.PENDING) {
                    lead.status = CallSequencer.Status.SKIPPED; skipped.add(i)
                }
            }
        }
        // The most recently dialed lead has an outcome nobody has tagged yet -> ask for it, never re-dial.
        var pending = saved.pendingOutcomeIndex
        if (pending < 0 && lastDialIndex in leads.indices &&
            last[lastDialIndex]?.type == JournalType.DIAL && leads[lastDialIndex].outcome == null
        ) pending = lastDialIndex
        return Result(
            saved.copy(leads = leads, pendingOutcomeIndex = pending),
            dialed.sorted(), skipped.sorted(), reverted.sorted()
        )
    }
}
