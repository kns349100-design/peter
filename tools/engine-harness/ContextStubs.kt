package android.content
import java.io.File
interface SharedPreferences {
    fun getBoolean(k: String, d: Boolean): Boolean; fun getString(k: String, d: String?): String?; fun getInt(k: String, d: Int): Int
    val all: Map<String, *>
    fun edit(): Editor
    interface Editor { fun putString(k: String, v: String?): Editor; fun putInt(k: String, v: Int): Editor; fun putBoolean(k: String, v: Boolean): Editor
        fun remove(k: String): Editor; fun clear(): Editor; fun apply(); fun commit(): Boolean }
}
class MemPrefs : SharedPreferences {
    val m = HashMap<String, Any?>()
    override val all: Map<String, *> get() = HashMap(m)
    override fun getBoolean(k: String, d: Boolean) = m[k] as? Boolean ?: d
    override fun getString(k: String, d: String?) = m[k] as? String ?: d
    override fun getInt(k: String, d: Int) = m[k] as? Int ?: d
    override fun edit() = object : SharedPreferences.Editor {
        override fun putString(k: String, v: String?) = apply { m[k] = v }
        override fun putInt(k: String, v: Int) = apply { m[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { m[k] = v }
        override fun remove(k: String) = apply { m.remove(k) }
        override fun clear() = apply { m.clear() }
        override fun apply() {}
        override fun commit(): Boolean = true
    }
}
open class Context(val filesDir: File, val cacheDir: File = java.nio.file.Files.createTempDirectory("cache").toFile()) {
    companion object { const val MODE_PRIVATE = 0; const val POWER_SERVICE = "power" }
    val applicationContext: Context get() = this
    val prefs = MemPrefs()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    var deleted = mutableListOf<String>()
    fun deleteSharedPreferences(name: String): Boolean { deleted.add(name); return true }
}
