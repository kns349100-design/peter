package com.autodialer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where pre-encryption values come from (the old plaintext SharedPreferences file). */
interface LegacyPrefs {
    fun readAll(): Map<String, Any?>
    /** Erase the plaintext copy. Only called after the encrypted copy is safely on disk. */
    fun wipe()
}

/**
 * Drop-in replacement for the SharedPreferences calls this app uses (getString / getInt / getLong / getBoolean /
 * getStringSet / contains / edit().putX().remove().clear().apply()/commit()), but the whole key-value map is
 * one AES-256-GCM encrypted, atomically written file. Used for the login phone number, license state, draft
 * numbers, outcomes and the call-log index - everything that used to sit in plaintext XML.
 *
 *  - Reads come from memory (loaded once), so hot paths like "has access?" stay fast.
 *  - apply() updates memory at once and writes in the background (coalesced); commit() writes before returning.
 *  - First run after an update: values are imported from the old plaintext file, written encrypted, and only THEN
 *    is the plaintext file wiped. If the encrypted write fails, the old file is kept (nothing is lost) and the
 *    import is retried next launch.
 *  - A Keystore that is down for a moment (transient) never causes data loss: nothing is overwritten, reads give
 *    defaults, edits made meanwhile are merged over the real data once the key is back. Data that is permanently
 *    undecryptable (key replaced / file tampered) is treated as empty.
 */
class SecurePrefs internal constructor(
    private val file: File,
    private val cipher: DataCipher,
    private val aad: String,
    private val legacy: LegacyPrefs?,
    private val writer: Executor,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var map = HashMap<String, Any>()
    private var loaded = false
    private var lastAttempt = 0L
    private var persistScheduled = false

    // Edits made while the key was unavailable; re-applied over the real data once it can be read.
    private var offlineClear = false
    private val offlineWrites = HashMap<String, Any>()
    private val offlineRemoved = HashSet<String>()

    companion object {
        private const val RETRY_MS = 2_000L
        private val registry = HashMap<String, SecurePrefs>()
        private val defaultWriter: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "prefs-writer").also { it.isDaemon = true } }

        /** One instance per file for the whole process (two instances over one file would overwrite each other). */
        fun open(context: Context, name: String): SecurePrefs = synchronized(registry) {
            registry.getOrPut(name) {
                val app = context.applicationContext
                SecurePrefs(File(app.filesDir, "prefs_$name.enc"), AndroidCrypto.cipher(app), "prefs:$name", SharedPrefsLegacy(app, name), defaultWriter)
            }
        }
    }

    private class SharedPrefsLegacy(private val ctx: Context, private val name: String) : LegacyPrefs {
        override fun readAll(): Map<String, Any?> = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
        override fun wipe() {
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            ctx.deleteSharedPreferences(name)
        }
    }

    // ---------------- loading ----------------

    private fun ensureLoaded() {                       // lock held
        if (loaded) return
        val now = clock()
        if (lastAttempt != 0L && now - lastAttempt < RETRY_MS) return    // key is down: don't hammer it on every get
        lastAttempt = now
        when (val r = SecureFile.read(file, cipher, aad)) {
            is SecureFile.Read.Missing -> { loaded = true; importLegacy() }     // loaded first: the import must be able to persist itself
            is SecureFile.Read.Unreadable -> { map = HashMap(); loaded = true }     // permanent: start fresh, next write replaces it
            is SecureFile.Read.KeyUnavailable -> { /* transient: stay unloaded, retry later */ }
            is SecureFile.Read.Data -> {
                var disk = parse(r.bytes)
                if (offlineClear) disk = HashMap()
                disk.putAll(offlineWrites); offlineRemoved.forEach { disk.remove(it) }
                val merged = offlineClear || offlineWrites.isNotEmpty() || offlineRemoved.isNotEmpty()
                offlineClear = false; offlineWrites.clear(); offlineRemoved.clear()
                map = disk; loaded = true
                if (r.wasPlaintext || merged) persistLocked()
            }
        }
    }

    private fun importLegacy() {
        val old = try { legacy?.readAll() } catch (e: Exception) { null } ?: return
        val imported = HashMap<String, Any>()
        for ((k, v) in old) when (v) {
            is String -> imported[k] = v
            is Boolean -> imported[k] = v
            is Int -> imported[k] = v.toLong()
            is Long -> imported[k] = v
            is Float -> imported[k] = v.toDouble()
            is Set<*> -> imported[k] = v.filterIsInstance<String>().toSet()
        }
        map = HashMap(imported)
        if (imported.isNotEmpty() && persistLocked()) try { legacy?.wipe() } catch (e: Exception) { }
    }

    private fun parse(bytes: ByteArray): HashMap<String, Any> {
        val out = HashMap<String, Any>()
        val o = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { return out }
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next() as String       // cast: some org.json builds return a raw Iterator
            val e = o.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "s" -> out[k] = e.optString("v", "")
                "l" -> out[k] = e.optLong("v", 0L)
                "b" -> out[k] = e.optBoolean("v", false)
                "d" -> out[k] = e.optDouble("v", 0.0)
                "set" -> { val a = e.optJSONArray("v") ?: JSONArray(); out[k] = (0 until a.length()).map { a.optString(it, "") }.toSet() }
            }
        }
        return out
    }

    private fun serialize(): ByteArray {
        val o = JSONObject()
        for ((k, v) in map) {
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Double -> e.put("t", "d").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /** @return true if the encrypted file now holds the current state. lock held. */
    private fun persistLocked(): Boolean = loaded && SecureFile.write(file, serialize(), cipher, aad)

    private fun schedulePersist() {                    // lock held
        if (persistScheduled) return
        persistScheduled = true
        writer.execute { synchronized(lock) { persistScheduled = false; if (loaded) persistLocked() } }
    }

    /** Waits (briefly) for queued background writes - used by tests and before the process is expected to die. */
    fun flush() {
        val done = CountDownLatch(1)
        try { writer.execute { done.countDown() }; done.await(3, TimeUnit.SECONDS) } catch (e: Exception) { }
    }

    // ---------------- reads ----------------

    fun getString(key: String, def: String?): String? = synchronized(lock) { ensureLoaded(); map[key] as? String ?: def }
    fun getBoolean(key: String, def: Boolean): Boolean = synchronized(lock) { ensureLoaded(); map[key] as? Boolean ?: def }
    fun getLong(key: String, def: Long): Long = synchronized(lock) { ensureLoaded(); (map[key] as? Number)?.toLong() ?: def }
    fun getInt(key: String, def: Int): Int = synchronized(lock) { ensureLoaded(); (map[key] as? Number)?.toInt() ?: def }
    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String, def: Set<String>?): Set<String>? = synchronized(lock) { ensureLoaded(); (map[key] as? Set<String>)?.toSet() ?: def }
    fun contains(key: String): Boolean = synchronized(lock) { ensureLoaded(); map.containsKey(key) }

    // ---------------- edits ----------------

    fun edit(): Editor = Editor()

    inner class Editor {
        private val puts = HashMap<String, Any>()
        private val removes = HashSet<String>()
        private var clear = false

        fun putString(k: String, v: String?): Editor { if (v == null) remove(k) else { puts[k] = v; removes.remove(k) }; return this }
        fun putBoolean(k: String, v: Boolean): Editor { puts[k] = v; removes.remove(k); return this }
        fun putLong(k: String, v: Long): Editor { puts[k] = v; removes.remove(k); return this }
        fun putInt(k: String, v: Int): Editor { puts[k] = v.toLong(); removes.remove(k); return this }
        fun putStringSet(k: String, v: Set<String>?): Editor { if (v == null) remove(k) else { puts[k] = v.toSet(); removes.remove(k) }; return this }
        fun remove(k: String): Editor { removes.add(k); puts.remove(k); return this }
        fun clear(): Editor { clear = true; return this }

        private fun applyToMemory() {                   // lock held; same order as SharedPreferences: clear first, then puts/removes
            ensureLoaded()
            if (clear) { map.clear(); if (!loaded) { offlineClear = true; offlineWrites.clear(); offlineRemoved.clear() } }
            map.putAll(puts); removes.forEach { map.remove(it) }
            if (!loaded) { offlineWrites.putAll(puts); puts.keys.forEach { offlineRemoved.remove(it) }; offlineRemoved.addAll(removes); removes.forEach { offlineWrites.remove(it) } }
        }

        fun apply() { synchronized(lock) { applyToMemory(); if (loaded) schedulePersist() } }

        /** @return true if the change is on disk (false while the key is unavailable - it stays in memory and is merged later). */
        fun commit(): Boolean = synchronized(lock) { applyToMemory(); persistLocked() }
    }
}
