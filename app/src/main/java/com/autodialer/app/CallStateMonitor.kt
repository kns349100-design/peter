package com.autodialer.app

import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Watches the phone state without touching the UI thread.
 *
 *  - Android 12+ (API 31+): TelephonyCallback, delivered on our own single-thread executor.
 *  - Android 10-11 (API 29-30): PhoneStateListener(executor) - same executor.
 *  - Android 7-9 (API 24-28): the executor-less PhoneStateListener is the only option; it must be
 *    created on a Looper thread, so register() has to be called from the main thread (CallEngine does).
 *
 * [onEvent] runs on the callback thread (NOT the main thread). It receives the event plus a
 * "still current?" check, and must hand the work to the main thread and re-check there, so that
 * everything which touches engine state stays on ONE thread.
 */
class CallStateMonitor(
    private val telephonyManager: TelephonyManager,
    private val onEvent: (event: CallStateTracker.Event, isStillCurrent: () -> Boolean) -> Unit
) {
    private val tracker = CallStateTracker()
    private var executor: ExecutorService? = null
    private var modernCallback: Any? = null   // TelephonyCallback (API 31+)
    private var legacyListener: Any? = null   // PhoneStateListener (API < 31)
    private var registered = false

    /** @throws SecurityException if READ_PHONE_STATE is not granted (Android 12+ enforces it). */
    @Synchronized fun register() {
        if (registered) return
        val gen = tracker.newGeneration()
        val sink: (Int) -> Unit = { state ->
            val event = tracker.onState(gen, state)
            if (event != null) onEvent(event) { tracker.isCurrent(gen) }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = ModernCallback(sink)
                telephonyManager.registerTelephonyCallback(executor(), cb)
                modernCallback = cb
            } else {
                registerLegacy(sink)
            }
            registered = true
        } catch (e: RuntimeException) {
            tracker.invalidate()
            throw e
        }
    }

    @Synchronized fun unregister() {
        tracker.invalidate() // drop anything still queued, even if the OS call below fails
        if (!registered) return
        registered = false
        try {
            val modern = modernCallback
            if (modern != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(modern as TelephonyCallback)
            }
            val legacy = legacyListener
            if (legacy != null) unregisterLegacy(legacy)
        } catch (e: RuntimeException) {
            // already gone - nothing to do
        }
        modernCallback = null
        legacyListener = null
    }

    /** Unregisters and stops the callback thread. Call from the owner's teardown so no thread is left behind. */
    @Synchronized fun shutdown() {
        unregister()
        executor?.shutdown()
        executor = null
    }

    private fun executor(): ExecutorService =
        executor ?: Executors.newSingleThreadExecutor { r -> Thread(r, "call-state").apply { isDaemon = true } }
            .also { executor = it }

    @Suppress("DEPRECATION")
    private fun registerLegacy(sink: (Int) -> Unit) {
        val listener: PhoneStateListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : PhoneStateListener(executor()) {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = sink(state)
            }
        } else {
            object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = sink(state)
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyListener = listener
    }

    @Suppress("DEPRECATION")
    private fun unregisterLegacy(listener: Any) {
        telephonyManager.listen(listener as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class ModernCallback(private val sink: (Int) -> Unit) :
        TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = sink(state)
    }
}
