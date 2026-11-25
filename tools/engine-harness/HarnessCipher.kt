package com.autodialer.app
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
class HKey(val k: SecretKey) : KeyProvider { override fun key() = k }
object HarnessCipherKey { @Volatile var last: SecretKey? = null }
object HarnessCipher {
    fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    fun cipher(k: SecretKey = newKey()): DataCipher { HarnessCipherKey.last = k; return AesGcmCipher(HKey(k)) }
    val shared: DataCipher = cipher()
    val broken: DataCipher = AesGcmCipher(object : KeyProvider { override fun key(): SecretKey = throw IllegalStateException("keystore down") })
}
