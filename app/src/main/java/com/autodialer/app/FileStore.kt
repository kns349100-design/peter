package com.autodialer.app

import android.content.Context
import java.io.File

/**
 * Internal-storage file read/write for larger JSON blobs (a long number list, or a day with
 * thousands of call-log rows). SharedPreferences keeps its whole file in memory and rewrites
 * it on every single change, which gets noticeably slow - and memory-heavy - once one value
 * inside it is this big. Plain files don't have that problem and comfortably handle lists in
 * the thousands.
 *
 * Writes are atomic: the new content goes to a temporary file first and only then replaces the
 * real file. If the phone dies, the battery is pulled or Android kills the app half-way through
 * a save, the previous complete file is still there - never a half-written, unreadable one.
 */
object FileStore {

    /** Replaceable so the whole thing can be tested on a plain JVM. */
    @Volatile var cipherFactory: (Context) -> DataCipher = { AndroidCrypto.cipher(it) }

    /** One lock for every write and for the background migration, so the migration can never overwrite a newer save. */
    private val lock = Any()

    private fun aad(filename: String) = "filestore:$filename"

    /** Encrypted (AES-256-GCM) + atomic + fsynced. Failure never crashes the app and never degrades to plaintext. */
    fun writeString(context: Context, filename: String, content: String) {
        try {
            synchronized(lock) {
                SecureFile.write(File(context.filesDir, filename), content.toByteArray(Charsets.UTF_8), cipherFactory(context), aad(filename))
            }
        } catch (e: Exception) {
            // Best-effort - a failed save should never crash the app.
        }
    }

    /** Decrypted text; an old plaintext file is still readable (and is re-encrypted by [encryptLegacyFiles]). Null if missing/unreadable. */
    fun readString(context: Context, filename: String): String? = try {
        when (val r = SecureFile.read(File(context.filesDir, filename), cipherFactory(context), aad(filename))) {
            is SecureFile.Read.Data -> String(r.bytes, Charsets.UTF_8)
            else -> null
        }
    } catch (e: Exception) { null }

    fun delete(context: Context, filename: String) {
        try {
            synchronized(lock) { DurableFile.delete(File(context.filesDir, filename)) }
        } catch (e: Exception) {
            // Ignore
        }
    }

    /** Plain-directory variants - used ONLY for the pre-v2 session file, which was never encrypted. */
    fun readString(dir: java.io.File, filename: String): String? = try {
        val file = File(dir, filename)
        if (!file.exists()) null else file.readText()
    } catch (e: Exception) { null }

    fun delete(dir: java.io.File, filename: String) {
        try { File(dir, filename).delete(); File(dir, "$filename.tmp").delete() } catch (e: Exception) { }
    }

    /**
     * One-time clean-up for installs that already have plaintext call-log files: re-writes every day file whose
     * name matches [prefix]..[suffix] encrypted. Safe to run repeatedly and while the app is saving.
     * @return how many files were converted.
     */
    fun encryptLegacyFiles(context: Context, prefix: String = "calllog_", suffix: String = ".json"): Int {
        var converted = 0
        val cipher = try { cipherFactory(context) } catch (e: Exception) { return 0 }
        val files = context.filesDir.listFiles() ?: return 0
        for (f in files) {
            if (!f.name.startsWith(prefix) || !f.name.endsWith(suffix)) continue
            try {
                synchronized(lock) {
                    // Re-read under the lock: if the app saved a newer (encrypted) version meanwhile, leave it alone.
                    val r = SecureFile.read(f, cipher, aad(f.name))
                    if (r is SecureFile.Read.Data && r.wasPlaintext && SecureFile.write(f, r.bytes, cipher, aad(f.name))) converted++
                }
            } catch (e: Exception) { }
        }
        return converted
    }

    /** Shared CSV exports contain phone numbers in the clear by nature: don't leave them lying in the cache. */
    fun purgeStaleExports(context: Context, maxAgeMs: Long = 10 * 60_000L, now: Long = System.currentTimeMillis()): Int {
        var deleted = 0
        val stale = { f: File -> f.isFile && now - f.lastModified() > maxAgeMs }
        val dirs = listOf(File(context.cacheDir, "exports"), context.cacheDir)
        for (d in dirs) {
            for (f in d.listFiles() ?: continue) {
                val isExport = d.name == "exports" || (f.name.startsWith("call_log_") && f.name.endsWith(".csv"))
                if (isExport && stale(f) && f.delete()) deleted++
            }
        }
        return deleted
    }
}
