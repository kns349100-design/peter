package com.autodialer.app
data class Lead(val name: String?, val phone: String, var status: CallSequencer.Status = CallSequencer.Status.PENDING, var outcome: String? = null)
data class SavedSession(val sessionName: String, val leads: List<Lead>, val currentIndex: Int, val delaySeconds: Int, val batchTarget: Int = 0,
    val pendingOutcomeIndex: Int = -1, val pendingLogDate: String? = null, val pendingLogIndex: Int = -1, val awaitingWhatsAppReturn: Boolean = false,
    val sessionId: String = "", val savedAt: Long = 0L)
