package android.net
class Uri(val s: String) { companion object { fun parse(s: String) = Uri(s) }; override fun toString() = s }
