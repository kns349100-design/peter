package com.autodialer.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Centralized flow for checking and requesting battery optimization exemptions. */
object BatteryExemptionFlow {

    @Volatile private var dialogShowing = false

    fun isIgnoring(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    private class PrefsStore(context: Context) : BatteryPromptStore {
        private val p = context.getSharedPreferences("autodialer_battery_prompt", Context.MODE_PRIVATE)
        override fun load(): BatteryPromptState {
            val legacy = if (p.getBoolean("asked", false) && p.getInt("dismissCount", -1) < 0) 1 else 0
            return BatteryPromptState(
                p.getInt("dismissCount", legacy),
                p.getLong("lastDismissedAt", 0L),
                p.getBoolean("oemGuideShown", false)
            )
        }

        override fun save(s: BatteryPromptState) {
            p.edit()
                .putInt("dismissCount", s.dismissCount)
                .putLong("lastDismissedAt", s.lastDismissedAt)
                .putBoolean("oemGuideShown", s.oemGuideShown)
                .apply()
        }
    }

    private fun oem() = OemDetector.detect(Build.MANUFACTURER, Build.BRAND)
    private fun advisor(context: Context) = BatteryExemptionAdvisor(oem(), PrefsStore(context))

    private fun openExemptionScreen(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(activity, "Unable to open battery settings on this device.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openOemScreen(activity: Activity) {
        val pm = activity.packageManager
        for (t in OemGuide.targets(oem())) {
            val i = Intent().setComponent(ComponentName(t.pkg, t.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) != null) {
                try {
                    activity.startActivity(i)
                    return
                } catch (_: Exception) {
                }
            }
        }
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
        } catch (_: Exception) {
            Toast.makeText(activity, "Unable to open app settings on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    fun gateThen(activity: Activity, proceed: () -> Unit) {
        if (dialogShowing) return
        val advisor = advisor(activity)
        when (advisor.decide(isIgnoring(activity))) {
            BatteryAction.NONE -> proceed()
            BatteryAction.ASK_EXEMPTION -> {
                dialogShowing = true
                AlertDialog.Builder(activity)
                    .setTitle("Keep auto-dialing active")
                    .setMessage(OemGuide.exemptionMessage(oem()))
                    .setCancelable(false)
                    .setPositiveButton("Allow") { _, _ ->
                        dialogShowing = false
                        openExemptionScreen(activity)
                    }
                    .setNegativeButton("Not now") { _, _ ->
                        dialogShowing = false
                        advisor.onDismissed()
                        proceed()
                    }
                    .show()
            }
            BatteryAction.ASK_OEM_GUIDE -> {
                dialogShowing = true
                advisor.onOemGuideShown()
                AlertDialog.Builder(activity)
                    .setTitle("Additional background setting")
                    .setMessage(OemGuide.guideMessage(oem()))
                    .setCancelable(false)
                    .setPositiveButton("Open Settings") { _, _ ->
                        dialogShowing = false
                        openOemScreen(activity)
                    }
                    .setNegativeButton("Skip") { _, _ ->
                        dialogShowing = false
                        proceed()
                    }
                    .show()
            }
        }
    }

    fun openFromSettings(activity: Activity) {
        if (!isIgnoring(activity)) {
            openExemptionScreen(activity)
            return
        }
        if (!OemGuide.hasGuide(oem())) {
            Toast.makeText(activity, "Background execution is already enabled.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Battery optimization disabled")
            .setMessage(OemGuide.guideMessage(oem()))
            .setPositiveButton("Open Settings") { _, _ -> openOemScreen(activity) }
            .setNegativeButton("Close", null)
            .show()
    }
}
