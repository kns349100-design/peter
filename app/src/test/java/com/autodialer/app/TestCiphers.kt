package com.autodialer.app

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** In-memory AES-256 key: the same AesGcmCipher the app uses, minus the Android Keystore. */
class FixedKey(val k: SecretKey) : KeyProvider { override fun key() = k }

object TestCiphers {
    fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    fun cipher(key: SecretKey = newKey()): AesGcmCipher = AesGcmCipher(FixedKey(key))
    /** A cipher whose key is unavailable, like a Keystore that is temporarily down. */
    fun broken(): AesGcmCipher = AesGcmCipher(object : KeyProvider { override fun key(): SecretKey = throw IllegalStateException("keystore down") })
}
