package com.autodialer.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

interface CallEngineListener {
    fun onLeadUpdated(index: Int, status: CallSequencer.Status)
    fun onDialing(index: Int, lead: Lead)
    fun onCallEndedAwaitingOutcome(index: Int)
    fun onBatchComplete(callsDone: Int)
    fun onSessionPaused()
    fun onSessionResumed()
    fun onSessionStopped()
    fun onSessionComplete()
    fun onEngineError(message: String)
    fun onLog(message: String)
}

class CallEngine(
    private val activity: Activity,
    private val sessionStore: SessionStore,
    private val callLogStore: CallLogStore,
    private val listener: CallEngineListener,
    /** Foreground service + wake lock while dialing. Defaults to "nothing" so tests / other callers are unaffected. */
    private val keepAlive: SessionKeepAlive = SessionKeepAlive.NONE
) {
    var leads: MutableList<Lead> = mutableListOf()
        private set

    private var sequencer: CallSequencer? = null
    private var sessionName: String = "Untitled Session"

    /** 0 = unlimited (dial the whole list without stopping). */
    var batchTarget: Int = 0
    private var callsDialedThisBatch = 0

    /** Index of the lead whose call just ended and is waiting for the user to tag an outcome. */
    private var pendingOutcomeIndex: Int? = null

    /** The call-log row created the instant a number is dialed, so it counts as "called" even if no outcome ever gets tagged. */
    private var pendingLogDate: String? = null
    private var pendingLogIndex: Int? = null

    /** True right after the app sent the user to WhatsApp for the INFO outcome and is waiting for them to return. */
    private var awaitingWhatsAppReturn = false

    fun isAwaitingWhatsAppReturn(): Boolean = awaitingWhatsAppReturn

    fun markAwaitingWhatsAppReturn() {
        awaitingWhatsAppReturn = true
        persist()
    }

    private val telephonyManager =
        activity.getSystemService(Activity.TELEPHONY_SERVICE) as TelephonyManager
    private val handler = Handler(Looper.getMainLooper())

    private var listenerRegistered = false

    private val monitor = CallStateMonitor(telephonyManager) { event, isStillCurrent ->
        handler.post {
            if (!isStillCurrent()) return@post
            when (event) {
                CallStateTracker.Event.CONNECTED -> sequencer?.onCallConnected()
                CallStateTracker.Event.ENDED -> handleCallEnded()
            }
        }
    }

    fun loadLeads(newLeads: List<Lead>, name: String) {
        leads = newLeads.toMutableList()
        sessionName = name
        sequencer = CallSequencer(leads.size)
        sessionStore.beginSession()
        persist()
    }

    var restoreSummary: String? = null
        private set

    fun restoreIfAvailable(): Boolean {
        val saved = sessionStore.load() ?: return false
        leads = saved.leads.toMutableList()
        sessionName = saved.sessionName
        batchTarget = saved.batchTarget
        sequencer = CallSequencer(leads.size)

        pendingLogDate = saved.pendingLogDate
        pendingLogIndex = if (saved.pendingLogIndex >= 0) saved.pendingLogIndex else null
        awaitingWhatsAppReturn = saved.awaitingWhatsAppReturn

        var recoveredPendingOutcome: Int? = null
        for (i in leads.indices) {
            if (leads[i].status == CallSequencer.Status.CALLING) {
                leads[i].status = CallSequencer.Status.COMPLETED
                recoveredPendingOutcome = i
            }
            sequencer!!.restoreStatus(i, leads[i].status)
        }

        pendingOutcomeIndex = when {
            saved.pendingOutcomeIndex >= 0 && saved.pendingOutcomeIndex in leads.indices -> saved.pendingOutcomeIndex
            recoveredPendingOutcome != null -> recoveredPendingOutcome
            else -> null
        }

        val done = leads.count { it.status == CallSequencer.Status.COMPLETED || it.status == CallSequencer.Status.SKIPPED }
        restoreSummary = "$sessionName - $done of ${leads.size} done"
        listener.onLog("Session restored: $sessionName")
        return true
    }

    private fun registerListener(): Boolean {
        if (listenerRegistered) return true
        return try {
            monitor.register()
            listenerRegistered = true
            true
        } catch (e: SecurityException) {
            listener.onEngineError("Phone permission missing. Please allow Phone permission and try again.")
            false
        } catch (e: RuntimeException) {
            listener.onEngineError("Unable to monitor phone call state.")
            false
        }
    }

    private fun unregisterListener() {
        monitor.unregister()
        listenerRegistered = false
    }

    private fun allLeadsFinal(): Boolean =
        leads.isNotEmpty() && leads.all {
            it.status == CallSequencer.Status.COMPLETED || it.status == CallSequencer.Status.SKIPPED
        }

    private fun firstPendingIndex(): Int? =
        leads.indices.firstOrNull { leads[it].status == CallSequencer.Status.PENDING }

    private fun finishOrResync() {
        if (allLeadsFinal()) {
            finishSession()
            return
        }
        val pendingIndex = firstPendingIndex()
        if (pendingIndex == null) {
            finishSession()
            return
        }
        val resumed = sequencer?.forceCallingAt(pendingIndex)
        if (resumed != null) {
            dial(resumed)
        } else {
            finishSession()
        }
    }

    fun start() {
        val seq = sequencer
        if (seq == null || leads.isEmpty()) {
            listener.onEngineError("Contact list is empty.")
            return
        }
        if (pendingOutcomeIndex != null) {
            registerListener()
            listener.onCallEndedAwaitingOutcome(pendingOutcomeIndex!!)
            return
        }
        if (!registerListener()) return
        keepAlive.onActive("Dialer running - ${leads.count { it.status == CallSequencer.Status.COMPLETED || it.status == CallSequencer.Status.SKIPPED }} of ${leads.size} done")
        callsDialedThisBatch = 0
        val index = if (seq.currentIndex == -1) seq.start() else seq.continueSequence()
        if (index != null) {
            dial(index)
        } else if (seq.isComplete()) {
            finishOrResync()
        }
    }

    fun pause() {
        sequencer?.pause()
        keepAlive.onInactive()
        listener.onSessionPaused()
        persist()
    }

    fun resume() = start()

    fun stop() {
        sequencer?.stop()
        val interrupted = sequencer?.takeInterrupted() ?: -1
        if (interrupted in leads.indices) {
            leads[interrupted].status = CallSequencer.Status.COMPLETED
            listener.onLeadUpdated(interrupted, CallSequencer.Status.COMPLETED)
            pendingOutcomeIndex = interrupted
        }
        handler.removeCallbacksAndMessages(null)
        unregisterListener()
        keepAlive.onInactive()
        listener.onSessionStopped()
        persist()
    }

    fun skip() {
        val seq = sequencer ?: return
        if (seq.callActive || pendingOutcomeIndex != null) {
            return
        }
        val index = seq.currentIndex
        val leadForLog = if (index in leads.indices) leads[index] else null
        val result = seq.skip()
        val snapshot = seq.statuses
        for (i in leads.indices) {
            if (snapshot[i] == CallSequencer.Status.SKIPPED && leads[i].status != CallSequencer.Status.SKIPPED) {
                leads[i].status = CallSequencer.Status.SKIPPED
                sessionStore.journalSkip(i, leads[i].phone)
                listener.onLeadUpdated(i, CallSequencer.Status.SKIPPED)
            }
        }
        if (leadForLog != null) {
            val date = pendingLogDate
            val logIndex = pendingLogIndex
            if (date != null && logIndex != null) {
                callLogStore.updateEntry(date, logIndex, "Skipped", null)
            } else {
                callLogStore.addEntry(
                    callLogStore.todayKey(),
                    CallLogEntry(callLogStore.nowTime(), leadForLog.name, leadForLog.phone, "Skipped", null)
                )
            }
        }
        pendingLogDate = null
        pendingLogIndex = null
        if (result != null) dial(result)
        persist()
    }

    private val normCache = HashMap<String, String>()
    private fun norm(phone: String): String = normCache.getOrPut(phone) { PhoneUtils.normalize(phone) }

    private val maxSkipsPerSlice = 500

    private fun dial(index: Int) {
        val seq = sequencer ?: return
        if (index !in leads.indices) return

        val finished = HashSet<String>()
        for (l in leads) {
            if (l.status == CallSequencer.Status.COMPLETED || l.status == CallSequencer.Status.SKIPPED) {
                finished.add(norm(l.phone))
            }
        }

        var skippedCount = 0
        val step = DialResolver.resolve(
            seq = seq,
            first = index,
            maxSkips = maxSkipsPerSlice,
            isDuplicate = { i ->
                val phone = leads[i].phone
                if (!PhoneUtils.isValidDialableNumber(phone)) return@resolve true
                val n = norm(phone)
                n in finished || callLogStore.calledToday(n)
            },
            onSkipped = { i ->
                val lead = leads[i]
                skippedCount++
                val isInvalid = !PhoneUtils.isValidDialableNumber(lead.phone)
                lead.status = CallSequencer.Status.SKIPPED
                finished.add(norm(lead.phone))
                listener.onLeadUpdated(i, CallSequencer.Status.SKIPPED)
                val note = if (isInvalid) "Auto-skipped: invalid phone number" else "Auto-skipped: already called"
                callLogStore.addEntry(
                    callLogStore.todayKey(),
                    CallLogEntry(callLogStore.nowTime(), lead.name, lead.phone, "Skipped", null, note)
                )
            }
        )
        if (skippedCount > 0) persist()

        when (step) {
            is DialStep.Dial -> placeCall(step.index)
            is DialStep.Yield -> handler.post {
                val s = sequencer
                if (s != null && s.callActive && s.currentIndex == step.index) dial(step.index)
            }
            DialStep.Finished -> if (seq.isComplete()) finishOrResync()
        }
    }

    private fun placeCall(index: Int) {
        if (index !in leads.indices) return
        val lead = leads[index]

        if (!PhoneUtils.isValidDialableNumber(lead.phone)) {
            sequencer?.abortDial(index)
            leads[index].status = CallSequencer.Status.SKIPPED
            listener.onLeadUpdated(index, CallSequencer.Status.SKIPPED)
            persist()
            val next = sequencer?.advance()
            if (next != null) dial(next) else finishOrResync()
            return
        }

        leads[index].status = CallSequencer.Status.CALLING
        listener.onLeadUpdated(index, CallSequencer.Status.CALLING)
        listener.onDialing(index, lead)
        listener.onLog("Calling ${lead.phone}")

        if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) {
            sequencer?.abortDial(index)
            leads[index].status = CallSequencer.Status.PENDING
            listener.onLeadUpdated(index, CallSequencer.Status.PENDING)
            listener.onEngineError("Another call is currently active. Dialer paused.")
            stop()
            return
        }

        if (!sessionStore.journalDial(index, lead.phone)) {
            sequencer?.abortDial(index)
            leads[index].status = CallSequencer.Status.PENDING
            listener.onLeadUpdated(index, CallSequencer.Status.PENDING)
            listener.onEngineError("Unable to save call progress to storage. Dialer stopped.")
            stop()
            return
        }

        val intent = Intent(Intent.ACTION_CALL)
        intent.data = Uri.parse("tel:${lead.phone}")
        try {
            activity.startActivity(intent)
            sequencer?.markDialIssued(index)
            keepAlive.onProgress("Calling ${index + 1} of ${leads.size}")
            callsDialedThisBatch += 1
            val date = callLogStore.todayKey()
            val logIndex = callLogStore.addEntry(
                date,
                CallLogEntry(callLogStore.nowTime(), lead.name, lead.phone, "Dialed", null)
            )
            pendingLogDate = date
            pendingLogIndex = logIndex
            persist()
        } catch (e: Exception) {
            leads[index].status = CallSequencer.Status.PENDING
            sequencer?.abortDial(index)
            sessionStore.journalAbort(index, lead.phone)
            listener.onLeadUpdated(index, CallSequencer.Status.PENDING)
            listener.onEngineError(if (e is SecurityException) "Call permission missing." else "Unable to place the call.")
            stop()
        }
    }

    private fun handleCallEnded() {
        val seq = sequencer ?: return
        val ended = seq.onCallEnded()
        if (!ended) return
        val endedIndex = seq.lastEndedIndex().takeIf { it in leads.indices }
        if (endedIndex != null) {
            leads[endedIndex].status = CallSequencer.Status.COMPLETED
            listener.onLeadUpdated(endedIndex, CallSequencer.Status.COMPLETED)
            pendingOutcomeIndex = endedIndex
        }
        persist()

        if (seq.paused || seq.stopped) return

        if (endedIndex != null) {
            listener.onCallEndedAwaitingOutcome(endedIndex)
        }
    }

    fun pendingLead(): Lead? {
        val index = pendingOutcomeIndex ?: return null
        return leads.getOrNull(index)
    }

    fun selectOutcome(outcomeId: String) {
        val seq = sequencer ?: return
        val index = pendingOutcomeIndex ?: return
        if (index in leads.indices) {
            leads[index].outcome = outcomeId
            listener.onLeadUpdated(index, leads[index].status)
            val date = pendingLogDate
            val logIndex = pendingLogIndex
            if (date != null && logIndex != null) {
                callLogStore.updateEntry(date, logIndex, "Completed", outcomeId)
            } else {
                val lead = leads[index]
                callLogStore.addEntry(
                    callLogStore.todayKey(),
                    CallLogEntry(callLogStore.nowTime(), lead.name, lead.phone, "Completed", outcomeId)
                )
            }
        }
        pendingOutcomeIndex = null
        pendingLogDate = null
        pendingLogIndex = null
        awaitingWhatsAppReturn = false
        persist()

        if (batchTarget > 0 && callsDialedThisBatch >= batchTarget) {
            seq.pause()
            unregisterListener()
            listener.onBatchComplete(callsDialedThisBatch)
            return
        }

        val next = seq.advance()
        if (next != null) {
            dial(next)
        } else {
            finishOrResync()
        }
    }

    fun hasActiveList(): Boolean {
        if (leads.isEmpty()) return false
        if (pendingOutcomeIndex != null) return true
        return leads.any { it.status == CallSequencer.Status.PENDING || it.status == CallSequencer.Status.CALLING }
    }

    fun removeCurrentList() {
        unregisterListener()
        handler.removeCallbacksAndMessages(null)
        leads = mutableListOf()
        sequencer = null
        pendingOutcomeIndex = null
        pendingLogDate = null
        pendingLogIndex = null
        awaitingWhatsAppReturn = false
        sessionStore.clear()
    }

    private fun finishSession() {
        unregisterListener()
        keepAlive.onInactive()
        leads = mutableListOf()
        sequencer = null
        pendingOutcomeIndex = null
        pendingLogDate = null
        pendingLogIndex = null
        awaitingWhatsAppReturn = false
        sessionStore.clear()
        listener.onSessionComplete()
    }

    fun persist() {
        val seq = sequencer ?: return
        sessionStore.save(
            SavedSession(
                sessionName = sessionName,
                leads = leads,
                currentIndex = seq.currentIndex,
                delaySeconds = 0,
                batchTarget = batchTarget,
                pendingOutcomeIndex = pendingOutcomeIndex ?: -1,
                pendingLogDate = pendingLogDate,
                pendingLogIndex = pendingLogIndex ?: -1,
                awaitingWhatsAppReturn = awaitingWhatsAppReturn
            )
        )
    }

    fun teardown() {
        handler.removeCallbacksAndMessages(null)
        monitor.shutdown()
        listenerRegistered = false
        keepAlive.onInactive()
    }
}
