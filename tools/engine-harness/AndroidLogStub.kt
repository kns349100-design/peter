package android.util
object Log {
    const val VERBOSE = 2; const val DEBUG = 3; const val INFO = 4; const val WARN = 5; const val ERROR = 6
    val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    fun println(priority: Int, tag: String, msg: String): Int { lines.add("$priority/$tag: $msg"); return 0 }
}
