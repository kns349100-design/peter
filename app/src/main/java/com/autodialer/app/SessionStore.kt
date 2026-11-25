package com.autodialer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Lead(
    val name: String?,
    val phone: String,
    var status: CallSequencer.Status = CallSequencer.Status.PENDING,
    var outcome: String? = null
)

data class SavedSession(
    val sessionName: String,
    val leads: List<Lead>,
    val currentIndex: Int,
    val delaySeconds: Int,
    val batchTarget: Int = 0,
    /** -1 means "no call is awaiting an outcome tag". */
    val pendingOutcomeIndex: Int = -1,
    val pendingLogDate: String? = null,
    val pendingLogIndex: Int = -1,
    /** True right after the app sent the user to WhatsApp for the INFO outcome and is
     * waiting for them to come back before dialing the next number. Persisted so that if
     * Android kills the app in the background while WhatsApp is open (very common - WhatsApp
     * is heavy), the app still knows to resume this exact step on its own instead of the
     * flag silently resetting and leaving the flow stuck. */
    val awaitingWhatsAppReturn: Boolean = false,
    /** Which list this is. Journal records of any other session are ignored. Filled in by SessionStore. */
    val sessionId: String = "",
    /** When this checkpoint was taken (epoch ms). Filled in by SessionStore. */
    val savedAt: Long = 0L
)

/**
 * Crash-safe persistence for the active list.
 *
 *  1. CHECKPOINT (session_v2.json): the whole session - metadata AND leads - in ONE file, written
 *     atomically (temp + fsync + rename). The old layout kept the metadata in SharedPreferences and the
 *     leads in a second file, written separately and asynchronously, so a kill between the two left
 *     them disagreeing (e.g. "lead 4 is the pending outcome" but lead 4 still PENDING -> re-dialed).
 *     Written on a background thread; rapid saves collapse into one write of the newest state.
 *  2. JOURNAL (dial_journal.log): durable write-ahead records for dials and skips, so even a stale
 *     checkpoint can never lead to a number being called twice. See DialJournal / RecoveryReconciler.
 *
 * [dir] and [writer] are injectable so the whole thing runs (and is crash-tested) on the JVM.
 */
class SessionStore(
    context: Context,
    private val writer: Executor = defaultWriter,
    private val dir: File = context.applicationContext.filesDir,
    private val cipher: DataCipher = AndroidCrypto.cipher(context)
) {
    private val prefs = context.getSharedPreferences("autodialer_session", Context.MODE_PRIVATE)
    private val snapshotFile = File(dir, "session_v2.json")
    private val legacyLeadsFile = "session_leads.json"
    private val journal = DialJournal(File(dir, "dial_journal.log"), cipher)
    private val aad = "session_v2"

    /** True if the last load() found a saved session it could not decrypt (key lost / file tampered / Keystore down). */
    @Volatile var lastLoadUnreadable: Boolean = false
        private set
    private val pendingLock = Any()
    private var pending: SavedSession? = null

    @Volatile private var sessionId: String = ""

    companion object {
        private val defaultWriter: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "session-writer").also { it.isDaemon = true }
        }
    }

    /** Starts a brand-new list: new id, old journal discarded. */
    fun beginSession(): String {
        val id = UUID.randomUUID().toString()
        sessionId = id
        journal.delete()
        return id
    }

    /** Durable write-ahead record. Returns false if it could not be made durable - then do NOT dial. */
    fun journalDial(index: Int, phone: String): Boolean = journal.append(sessionId, JournalType.DIAL, index, phone)
    fun journalAbort(index: Int, phone: String): Boolean = journal.append(sessionId, JournalType.ABORT, index, phone)
    fun journalSkip(index: Int, phone: String): Boolean = journal.append(sessionId, JournalType.SKIP, index, phone)

    /** Waits (briefly) for queued checkpoint writes to reach disk. Cheap when nothing is pending. */
    fun flush() {
        val done = CountDownLatch(1)
        try {
            writer.execute { done.countDown() }
            done.await(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Ignore - the background write will still finish on its own.
        }
    }

    fun save(session: SavedSession) {
        // Snapshot taken here, on the calling thread, so later edits to the live list cannot leak into the write.
        val snapshot = session.copy(
            leads = session.leads.map { Lead(it.name, it.phone, it.status, it.outcome) },
            sessionId = if (session.sessionId.isNotEmpty()) session.sessionId else sessionId,
            savedAt = System.currentTimeMillis()
        )
        val needsSchedule: Boolean
        synchronized(pendingLock) {
            needsSchedule = pending == null
            pending = snapshot
        }
        if (needsSchedule) writer.execute { writeLatest() }
    }

    private fun writeLatest() {
        val s: SavedSession
        synchronized(pendingLock) {
            s = pending ?: return
            pending = null
        }
        val leads = JSONArray()
        for (lead in s.leads) {
            leads.put(JSONObject().put("name", lead.name ?: "").put("phone", lead.phone)
                .put("status", lead.status.name).put("outcome", lead.outcome ?: ""))
        }
        val json = JSONObject()
            .put("v", 2).put("sessionId", s.sessionId).put("savedAt", s.savedAt)
            .put("sessionName", s.sessionName).put("currentIndex", s.currentIndex)
            .put("delaySeconds", s.delaySeconds).put("batchTarget", s.batchTarget)
            .put("pendingOutcomeIndex", s.pendingOutcomeIndex)
            .put("pendingLogDate", s.pendingLogDate ?: "").put("pendingLogIndex", s.pendingLogIndex)
            .put("awaitingWhatsAppReturn", s.awaitingWhatsAppReturn)
            .put("leads", leads)
        SecureFile.write(snapshotFile, json.toString().toByteArray(Charsets.UTF_8), cipher, aad)
    }

    /** The recovered session: last checkpoint merged with the durable journal. Null if nothing usable. */
    fun load(): SavedSession? {
        flush()
        lastLoadUnreadable = false
        val saved = try { readSnapshot() ?: loadLegacy() } catch (e: Exception) {
            // An unreadable saved list must never crash the app on every launch - treat it as
            // "nothing to restore" (the numbers already called are safe in the call log).
            null
        } ?: return null
        if (saved.sessionId.isEmpty()) {
            // Pre-v2 list: give it an id now, so journaling works from the very next dial.
            beginSession()
            return saved
        }
        sessionId = saved.sessionId
        if (journal.hasLegacyPlaintext()) journal.encryptLegacy()      // scrub plaintext numbers left by an older version                       // keep journaling into the same session after a restart
        return RecoveryReconciler.reconcile(saved, journal.read(saved.sessionId)).session
    }

    private fun readSnapshot(): SavedSession? {
        val bytes: ByteArray = when (val r = SecureFile.read(snapshotFile, cipher, aad)) {
            is SecureFile.Read.Missing -> return null
            is SecureFile.Read.Unreadable, is SecureFile.Read.KeyUnavailable -> { lastLoadUnreadable = true; return null }
            is SecureFile.Read.Data -> {
                if (r.wasPlaintext) SecureFile.write(snapshotFile, r.bytes, cipher, aad)   // upgrade in place, right now
                r.bytes
            }
        }
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val array = o.getJSONArray("leads")
        val leads = ArrayList<Lead>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val status = try { CallSequencer.Status.valueOf(obj.getString("status")) } catch (e: Exception) { CallSequencer.Status.PENDING }
            leads.add(Lead(obj.optString("name", "").ifEmpty { null }, obj.getString("phone"), status,
                obj.optString("outcome", "").ifEmpty { null }))
        }
        return SavedSession(
            sessionName = o.optString("sessionName", ""),
            leads = leads,
            currentIndex = o.optInt("currentIndex", -1),
            delaySeconds = o.optInt("delaySeconds", 2),
            batchTarget = o.optInt("batchTarget", 0),
            pendingOutcomeIndex = o.optInt("pendingOutcomeIndex", -1),
            pendingLogDate = o.optString("pendingLogDate", "").ifEmpty { null },
            pendingLogIndex = o.optInt("pendingLogIndex", -1),
            awaitingWhatsAppReturn = o.optBoolean("awaitingWhatsAppReturn", false),
            sessionId = o.optString("sessionId", ""),
            savedAt = o.optLong("savedAt", 0L)
        )
    }

    /** Pre-v2 layout (SharedPreferences metadata + session_leads.json), so an in-progress list survives the update. */
    private fun loadLegacy(): SavedSession? {
        if (!prefs.getBoolean("hasSession", false)) return null
        val text = FileStore.readString(dir, legacyLeadsFile) ?: prefs.getString("leads", null) ?: return null
        val array = JSONArray(text)
        val leads = ArrayList<Lead>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val status = try { CallSequencer.Status.valueOf(obj.getString("status")) } catch (e: Exception) { CallSequencer.Status.PENDING }
            leads.add(Lead(obj.optString("name", "").ifEmpty { null }, obj.getString("phone"), status,
                obj.optString("outcome", "").ifEmpty { null }))
        }
        return SavedSession(
            sessionName = prefs.getString("sessionName", "") ?: "",
            leads = leads,
            currentIndex = prefs.getInt("currentIndex", -1),
            delaySeconds = prefs.getInt("delaySeconds", 2),
            batchTarget = prefs.getInt("batchTarget", 0),
            pendingOutcomeIndex = prefs.getInt("pendingOutcomeIndex", -1),
            pendingLogDate = prefs.getString("pendingLogDate", "")?.ifEmpty { null },
            pendingLogIndex = prefs.getInt("pendingLogIndex", -1),
            awaitingWhatsAppReturn = prefs.getBoolean("awaitingWhatsAppReturn", false)
        )
    }

    fun clear() {
        synchronized(pendingLock) { pending = null }
        journal.delete()
        sessionId = ""
        prefs.edit().clear().apply()
        // Queued behind any write already running, so a late save can't bring the file back.
        writer.execute {
            DurableFile.delete(snapshotFile)
            FileStore.delete(dir, legacyLeadsFile)
        }
    }

    fun hasSavedSession(): Boolean =
        DurableFile.exists(snapshotFile) || prefs.getBoolean("hasSession", false)
}
