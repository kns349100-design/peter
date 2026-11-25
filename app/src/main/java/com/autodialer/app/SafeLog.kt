package com.autodialer.app

import android.util.Log

/**
 * The ONLY way the app may write to logcat (a unit test fails the build if anything else does). Every message is
 * PII-scrubbed first. d()/i() print nothing unless [debugEnabled]; R8 also strips them from release builds.
 */
object SafeLog {
    @Volatile var debugEnabled = false
    /** Replaceable in tests. */
    @Volatile var sink: (priority: Int, tag: String, msg: String) -> Unit = { p, t, m -> Log.println(p, t, m) }

    fun d(tag: String, msg: String) { if (debugEnabled) sink(Log.DEBUG, tag, PiiScrubber.scrub(msg)) }
    fun i(tag: String, msg: String) { if (debugEnabled) sink(Log.INFO, tag, PiiScrubber.scrub(msg)) }
    fun w(tag: String, msg: String) = sink(Log.WARN, tag, PiiScrubber.scrub(msg))
    fun e(tag: String, msg: String, t: Throwable? = null) =
        sink(Log.ERROR, tag, PiiScrubber.scrub(msg + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")))
}
