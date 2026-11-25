package android.security.keystore
import java.security.spec.AlgorithmParameterSpec
object KeyProperties {
    const val PURPOSE_ENCRYPT = 1; const val PURPOSE_DECRYPT = 2
    const val BLOCK_MODE_GCM = "GCM"; const val ENCRYPTION_PADDING_NONE = "NoPadding"; const val KEY_ALGORITHM_AES = "AES"
}
class KeyGenParameterSpec private constructor() : AlgorithmParameterSpec {
    class Builder(alias: String, purposes: Int) {
        fun setBlockModes(vararg m: String) = this
        fun setEncryptionPaddings(vararg p: String) = this
        fun setKeySize(n: Int) = this
        fun setIsStrongBoxBacked(b: Boolean) = this
        fun build() = KeyGenParameterSpec()
    }
}
