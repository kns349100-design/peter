package com.autodialer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service shown while a dialing session is running. It owns no dialing logic - the engine
 * does - its job is to (1) show the persistent "dialer is active" notification and (2) raise the
 * process to foreground priority so Android does not kill it in the middle of a call.
 *
 * Android 14 requires a declared foreground-service type: this uses "specialUse" (see manifest).
 * START_NOT_STICKY: if the process dies there is no engine to drive, so the OS must not resurrect an
 * empty service. Session state is recovered by the app itself on next launch (SessionStore).
 */
class DialerService : Service() {

    companion object {
        const val EXTRA_LABEL = "label"
        private const val CHANNEL_ID = "dialer_active"
        private const val NOTIFICATION_ID = 4101
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Dialer running"
        ensureChannel()
        val notification = buildNotification(label)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dialer active", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("AutoDialer is running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** User swiped the app away: nothing can drive the session any more - release CPU and notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AndroidKeepAlive.onServiceGone()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AndroidKeepAlive.onServiceGone()   // wake lock must never outlive the service
        super.onDestroy()
    }
}
