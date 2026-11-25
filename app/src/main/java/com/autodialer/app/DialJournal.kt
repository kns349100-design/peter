package com.autodialer.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

enum class JournalType(val code: Char) { DIAL('D'), ABORT('A'), SKIP('S') }

data class JournalRecord(val type: JournalType, val index: Int, val phone: String, val ts: Long = 0L)

/**
 * Write-ahead journal for the things that must NEVER be forgotten, however the process dies:
 *   D = "a call to lead #i is about to be placed"   (written + fsynced BEFORE startActivity)
 *   A = "...it was not placed after all"             (cancels the D)
 *   S = "the user skipped lead #i"
 *
 * Append-only, one record per line, each line ENCRYPTED on its own (AES-256-GCM, see DataCipher):
 *     E1:<hex( MAGIC | IV | ciphertext | tag )>\n
 * so a durable write is still one small fsync, phone numbers never touch the disk in the clear, and a line torn
 * by a crash or altered afterwards simply fails authentication and is ignored. The next append never glues itself
 * onto a torn line.
 *
 * Old plaintext lines ("J1|...|crc", written before encryption existed) are still READ, and [encryptLegacy] rewrites
 * them encrypted, so an update in the middle of a session loses nothing.
 */
class DialJournal(private val file: File, private val cipher: DataCipher) {

    private val aad = "journal".toByteArray(Charsets.UTF_8)

    private fun crc(body: String): String {
        val c = CRC32(); c.update(body.toByteArray(Charsets.UTF_8)); return java.lang.Long.toHexString(c.value)
    }

    private fun clean(s: String) = s.replace('|', '_').replace('\n', ' ').replace('\r', ' ')

    private fun hex(b: ByteArray): String { val sb = StringBuilder(b.size * 2); for (x in b) sb.append("%02x".format(x)); return sb.toString() }
    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) { val v = s.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null; out[i] = v.toByte() }
        return out
    }

    private fun encryptedLine(sessionId: String, type: JournalType, index: Int, phone: String, ts: Long): String {
        val body = "${clean(sessionId)}|$ts|${type.code}|$index|${clean(phone)}"
        return "E1:" + hex(cipher.encrypt(body.toByteArray(Charsets.UTF_8), aad)) + "\n"
    }

    /** @return true only if the record is durably on disk. Callers must NOT dial when this is false. */
    @Synchronized fun append(sessionId: String, type: JournalType, index: Int, phone: String, ts: Long = System.currentTimeMillis()): Boolean {
        if (sessionId.isEmpty()) return false
        return try {
            val line = encryptedLine(sessionId, type, index, phone, ts)
            file.parentFile?.mkdirs()
            val needsNewline = file.exists() && file.length() > 0 && lastByteIsNotNewline()
            FileOutputStream(file, true).use { out ->
                if (needsNewline) out.write('\n'.code)          // fence off a line torn by an earlier crash
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            true
        } catch (e: IOException) {
            false
        } catch (e: CryptoException) {
            false       // no key -> no record -> the engine refuses to dial. Never a plaintext fallback.
        }
    }

    private fun lastByteIsNotNewline(): Boolean = try {
        RandomAccessFile(file, "r").use { raf -> raf.seek(raf.length() - 1); raf.read() != '\n'.code }
    } catch (e: IOException) { false }

    /** All intact records of [sessionId], in the order they were written. */
    @Synchronized fun read(sessionId: String): List<JournalRecord> = readAll().filter { it.first == clean(sessionId) }.map { it.second }

    /** (sessionId, record, wasLegacyPlaintext) for every intact line. */
    private fun parse(): List<Triple<String, JournalRecord, Boolean>> {
        if (!file.exists()) return emptyList()
        val text = try { file.readText(Charsets.UTF_8) } catch (e: IOException) { return emptyList() }
        val out = ArrayList<Triple<String, JournalRecord, Boolean>>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            if (line.startsWith("E1:")) {
                val blob = unhex(line.substring(3)) ?: continue
                val plain = try { cipher.decrypt(blob, aad) } catch (e: CryptoException) { null } ?: continue   // torn / tampered / other key
                val p = String(plain, Charsets.UTF_8).split('|')
                if (p.size != 5) continue
                val type = JournalType.values().firstOrNull { it.code.toString() == p[2] } ?: continue
                val index = p[3].toIntOrNull() ?: continue
                out.add(Triple(p[0], JournalRecord(type, index, p[4], p[1].toLongOrNull() ?: 0L), false))
            } else if (line.startsWith("J1|")) {                     // legacy plaintext line
                val cut = line.lastIndexOf('|')
                if (cut < 0) continue
                val body = line.substring(0, cut)
                if (crc(body) != line.substring(cut + 1)) continue
                val p = body.split('|')
                if (p.size != 6) continue
                val type = JournalType.values().firstOrNull { it.code.toString() == p[3] } ?: continue
                val index = p[4].toIntOrNull() ?: continue
                out.add(Triple(p[1], JournalRecord(type, index, p[5], p[2].toLongOrNull() ?: 0L), true))
            }
        }
        return out
    }

    private fun readAll(): List<Pair<String, JournalRecord>> = parse().map { it.first to it.second }

    /** True if any plaintext line from before encryption is still on disk. */
    @Synchronized fun hasLegacyPlaintext(): Boolean = parse().any { it.third } ||
        (file.exists() && try { file.readText(Charsets.UTF_8).lineSequence().any { it.startsWith("J1|") } } catch (e: IOException) { false })

    /** Rewrites the whole journal with every record encrypted (atomic). Plaintext never remains on disk. */
    @Synchronized fun encryptLegacy(): Boolean {
        return try {
            val sb = StringBuilder()
            for ((sid, r, _) in parse()) sb.append(encryptedLine(sid, r.type, r.index, r.phone, r.ts))
            DurableFile.writeAtomic(file, sb.toString().toByteArray(Charsets.UTF_8))
        } catch (e: CryptoException) { false }
    }

    @Synchronized fun delete() { file.delete() }
}
