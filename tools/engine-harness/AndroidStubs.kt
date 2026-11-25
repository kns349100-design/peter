// Minimal JVM stand-ins for the Android classes CallEngine touches, so the REAL CallEngine /
// CallStateMonitor / CallSequencer code can run in a plain JVM test. Not part of the APK build.
package android.os
import java.util.concurrent.ConcurrentLinkedQueue
object Build {
    object VERSION { @JvmField var SDK_INT: Int = 31 }
    object VERSION_CODES { const val P = 28; const val Q = 29; const val S = 31 }
}
class Looper { companion object { private val main = Looper(); fun getMainLooper() = main } }
/** All Handlers share ONE queue = "the main thread's message queue". The test drains it explicitly. */
class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper) {
    companion object {
        val queue = ConcurrentLinkedQueue<Runnable>()
        val posts = java.util.concurrent.atomic.AtomicInteger()
        fun drain(): Int { var n = 0; while (true) { val r = queue.poll() ?: break; r.run(); n++ }; return n }
    }
    fun post(r: Runnable): Boolean { posts.incrementAndGet(); queue.add(r); return true }
    fun removeCallbacksAndMessages(token: Any?) { queue.clear() }
}
