package com.autodialer.app

import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Supplies the AES-256 key. In the app it lives in the Android Keystore (see AndroidCrypto). */
interface KeyProvider { fun key(): SecretKey }

interface DataCipher {
    /** @throws CryptoException if the key is unavailable. Callers must treat that as "do not write" - never fall back to plaintext. */
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray

    /**
     * @return the plaintext, or null if [blob] is not ours / was modified / belongs to another purpose (AAD) / was
     *         encrypted with another key.
     * @throws CryptoException if the key itself is unavailable (a transient Keystore problem must never be mistaken
     *         for "the data is garbage").
     */
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray?
}

object CipherFormat {
    /** "ADE" + format version 1. Lets us tell encrypted files from legacy plaintext, and lets the format evolve. */
    val MAGIC = byteArrayOf('A'.code.toByte(), 'D'.code.toByte(), 'E'.code.toByte(), 1)
    const val IV_LEN = 12
    const val TAG_BITS = 128
    private const val MIN_LEN = 4 + IV_LEN + 16

    fun looksEncrypted(b: ByteArray): Boolean =
        b.size >= MIN_LEN && b[0] == MAGIC[0] && b[1] == MAGIC[1] && b[2] == MAGIC[2] && b[3] == MAGIC[3]
}

/**
 * AES-256-GCM (authenticated encryption): confidentiality AND tamper detection. Blob layout:
 *   MAGIC(4) | IV(12, random per message) | ciphertext | GCM tag(16)
 * The AAD ("additional authenticated data") binds a blob to its purpose, so a valid blob cannot be copied
 * from one file/record type into another (e.g. a call-log file swapped in as the session checkpoint).
 * A fresh Cipher is created per call (Cipher objects are not thread-safe).
 */
class AesGcmCipher(private val keys: KeyProvider) : DataCipher {

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val key = keyOrThrow()
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)          // the provider (Keystore) picks a fresh random IV
            c.updateAAD(aad)
            val ct = c.doFinal(plain)
            val iv = c.iv
            if (iv == null || iv.size != CipherFormat.IV_LEN) throw CryptoException("unexpected IV length")
            return CipherFormat.MAGIC + iv + ct
        } catch (e: GeneralSecurityException) {
            throw CryptoException("encrypt failed", e)
        }
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray? {
        if (!CipherFormat.looksEncrypted(blob)) return null
        val key = keyOrThrow()
        return try {
            val iv = blob.copyOfRange(4, 4 + CipherFormat.IV_LEN)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(CipherFormat.TAG_BITS, iv))
            c.updateAAD(aad)
            c.doFinal(blob, 4 + CipherFormat.IV_LEN, blob.size - 4 - CipherFormat.IV_LEN)
        } catch (e: GeneralSecurityException) {
            null        // bad tag: tampered, wrong key, or wrong purpose
        }
    }

    private fun keyOrThrow(): SecretKey = try { keys.key() } catch (e: Exception) { throw CryptoException("key unavailable", e) }
}

/** Crash-safe (DurableFile) + encrypted whole-file storage. */
object SecureFile {
    sealed class Read {
        object Missing : Read()
        /** Present but cannot be decrypted (tampered, or encrypted with a key that no longer exists). Permanent. */
        object Unreadable : Read()
        /** The key could not be obtained right now (Keystore busy / temporarily down). Transient: the data is fine - retry later, never overwrite. */
        object KeyUnavailable : Read()
        /** [wasPlaintext] = an old, pre-encryption file: usable, and the caller should re-write it encrypted. */
        class Data(val bytes: ByteArray, val wasPlaintext: Boolean) : Read()
    }

    fun write(file: File, plain: ByteArray, cipher: DataCipher, aad: String): Boolean = try {
        DurableFile.writeAtomic(file, cipher.encrypt(plain, aad.toByteArray(Charsets.UTF_8)))
    } catch (e: CryptoException) {
        false       // fail closed: no key, no write - the data is never stored in the clear
    }

    fun read(file: File, cipher: DataCipher, aad: String): Read {
        val raw = DurableFile.readBytes(file) ?: return Read.Missing
        if (!CipherFormat.looksEncrypted(raw)) return Read.Data(raw, wasPlaintext = true)
        return try {
            val plain = cipher.decrypt(raw, aad.toByteArray(Charsets.UTF_8))
            if (plain == null) Read.Unreadable else Read.Data(plain, wasPlaintext = false)
        } catch (e: CryptoException) {
            Read.KeyUnavailable
        }
    }
}
