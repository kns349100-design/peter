package com.autodialer.app

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** One process-wide controller shared by the engine and by DialerService, so both always see the same state. */
object AndroidKeepAlive {
    @Volatile private var controller: KeepAliveController? = null

    fun get(context: Context): KeepAliveController {
        controller?.let { return it }
        synchronized(this) {
            controller?.let { return it }
            val app = context.applicationContext
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoDialer:dialing").apply {
                setReferenceCounted(false)     // acquire() twice + release() once = released
            }
            val c = KeepAliveController(ServiceHostImpl(app), WakeLockImpl(wl))
            controller = c
            return c
        }
    }

    /** Called by DialerService when it is destroyed or the app task is swiped away. */
    fun onServiceGone() { controller?.onHostGone() }

    private class WakeLockImpl(private val wl: PowerManager.WakeLock) : WakeLockLike {
        override val isHeld: Boolean get() = wl.isHeld
        override fun acquire(timeoutMs: Long) = wl.acquire(timeoutMs)   // timeout => the OS releases it by itself
        override fun release() { if (wl.isHeld) wl.release() }
    }

    private class ServiceHostImpl(private val app: Context) : ServiceHost {
        private fun intent(label: String) = Intent(app, DialerService::class.java).putExtra(DialerService.EXTRA_LABEL, label)
        override fun start(label: String) = ContextCompat.startForegroundService(app, intent(label))
        override fun update(label: String) = ContextCompat.startForegroundService(app, intent(label))
        override fun stop() { app.stopService(Intent(app, DialerService::class.java)) }
    }
}
