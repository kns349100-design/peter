package com.autodialer.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * One AES-256 key in the Android Keystore (hardware-backed / TEE where the phone has it, StrongBox when
 * available). The key never leaves the secure hardware, is not included in any backup and is deleted on uninstall,
 * so encrypted files copied off the phone (adb, file explorer, cloud backup, rooted pull) are unreadable.
 */
object AndroidCrypto {
    private const val ALIAS = "autodialer_data_key_v1"
    @Volatile private var cached: DataCipher? = null

    fun cipher(@Suppress("UNUSED_PARAMETER") context: Context): DataCipher =
        cached ?: synchronized(this) { cached ?: AesGcmCipher(KeystoreKeyProvider(ALIAS)).also { cached = it } }
}

class KeystoreKeyProvider(private val alias: String) : KeyProvider {
    @Volatile private var cached: SecretKey? = null

    override fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val k = loadOrCreate()
            cached = k
            return k
        }
    }

    private fun loadOrCreate(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        } catch (e: UnrecoverableKeyException) {
            // The key is permanently unusable (a known OEM Keystore corruption). Replace it; whatever it
            // protected was unreadable anyway. Any OTHER error is rethrown on purpose: a transient Keystore
            // hiccup must never cause us to delete a good key and lose data.
            ks.deleteEntry(alias)
        }
        return generate()
    }

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        fun make(strongBox: Boolean): SecretKey {
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(spec(strongBox))
            return g.generateKey()
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) make(true) else make(false)
        } catch (e: ProviderException) {
            make(false)     // StrongBoxUnavailableException is a ProviderException: this phone has no StrongBox
        }
    }
}
