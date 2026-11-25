package com.autodialer.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe whole-file writes using only java.io (works on Android and on the JVM).
 *
 * write: bytes -> "<name>.tmp", fsync, then rename over "<name>". rename() is atomic on Linux/Android
 * file systems, so at ANY instant a reader (or the next process after a kill / OOM / battery pull)
 * sees either the complete OLD file or the complete NEW file - never a half-written one.
 */
object DurableFile {
    fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val tmp = File(target.path + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()                    // force it to the disk, not just the OS cache
            }
            if (!tmp.renameTo(target)) {
                // A few file systems refuse rename-over. The target is removed only AFTER the temp file is
                // complete and synced, and readBytes() falls back to the temp file, so nothing is ever lost.
                target.delete()
                if (!tmp.renameTo(target)) return false
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    /** The real file; or, only if it is missing (crash between delete and rename), the finished temp file. */
    fun readBytes(target: File): ByteArray? = try {
        when {
            target.exists() -> target.readBytes()
            File(target.path + ".tmp").exists() -> File(target.path + ".tmp").readBytes()
            else -> null
        }
    } catch (e: IOException) {
        null
    }

    fun exists(target: File): Boolean = target.exists() || File(target.path + ".tmp").exists()

    fun delete(target: File) {
        target.delete()
        File(target.path + ".tmp").delete()
    }
}
