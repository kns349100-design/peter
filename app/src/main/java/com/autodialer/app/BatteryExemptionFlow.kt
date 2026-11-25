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

/** The one place that checks / requests the battery exemption (was copy-pasted in MainActivity and SettingsActivity). */
object BatteryExemptionFlow {

    @Volatile private var dialogShowing = false

    fun isIgnoring(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    private class PrefsStore(context: Context) : BatteryPromptStore {
        private val p = context.getSharedPreferences("autodialer_battery_prompt", Context.MODE_PRIVATE)
        override fun load(): BatteryPromptState {
            // "asked" is the old one-shot flag: that user has already been asked once.
            val legacy = if (p.getBoolean("asked", false) && p.getInt("dismissCount", -1) < 0) 1 else 0
            return BatteryPromptState(p.getInt("dismissCount", legacy), p.getLong("lastDismissedAt", 0L), p.getBoolean("oemGuideShown", false))
        }
        override fun save(s: BatteryPromptState) {
            p.edit().putInt("dismissCount", s.dismissCount).putLong("lastDismissedAt", s.lastDismissedAt).putBoolean("oemGuideShown", s.oemGuideShown).apply()
        }
    }

    private fun oem() = OemDetector.detect(Build.MANUFACTURER, Build.BRAND)
    private fun advisor(context: Context) = BatteryExemptionAdvisor(oem(), PrefsStore(context))

    /** Opens the system "let this app ignore battery optimization" dialog; falls back to the general list. */
    private fun openExemptionScreen(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${activity.packageName}")))
        } catch (e: Exception) {
            try { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e2: Exception) { Toast.makeText(activity, "Couldn't open battery settings on this phone", Toast.LENGTH_SHORT).show() }
        }
    }

    /** The maker's own autostart / background screen if one exists on THIS phone, else the app's settings page. */
    private fun openOemScreen(activity: Activity) {
        val pm = activity.packageManager
        for (t in OemGuide.targets(oem())) {
            val i = Intent().setComponent(ComponentName(t.pkg, t.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) != null) {
                try { activity.startActivity(i); return } catch (e: Exception) { /* try the next one */ }
            }
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}")))
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't open settings on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Call when the user presses Start. Shows at most one explanation (only when it is useful and not recently
     * refused) and then runs [proceed]. It NEVER blocks dialing: "Not now" / "Skip" starts the session anyway.
     * "Allow" / "Open settings" sends the user to the system screen instead; they press Start again when back.
     */
    fun gateThen(activity: Activity, proceed: () -> Unit) {
        if (dialogShowing) return
        val advisor = advisor(activity)
        when (advisor.decide(isIgnoring(activity))) {
            BatteryAction.NONE -> proceed()
            BatteryAction.ASK_EXEMPTION -> {
                dialogShowing = true
                AlertDialog.Builder(activity)
                    .setTitle("Keep your call list running")
                    .setMessage(OemGuide.exemptionMessage(oem()))
                    .setCancelable(false)
                    .setPositiveButton("Allow") { _, _ -> dialogShowing = false; openExemptionScreen(activity) }
                    .setNegativeButton("Not now") { _, _ -> dialogShowing = false; advisor.onDismissed(); proceed() }
                    .show()
            }
            BatteryAction.ASK_OEM_GUIDE -> {
                dialogShowing = true
                advisor.onOemGuideShown()                                  // shown once, never nagged again
                AlertDialog.Builder(activity)
                    .setTitle("One more phone setting")
                    .setMessage(OemGuide.guideMessage(oem()))
                    .setCancelable(false)
                    .setPositiveButton("Open settings") { _, _ -> dialogShowing = false; openOemScreen(activity) }
                    .setNegativeButton("Skip") { _, _ -> dialogShowing = false; proceed() }
                    .show()
            }
        }
    }

    /** Settings > "Keep App Running": always acts, whatever the cooldown says. */
    fun openFromSettings(activity: Activity) {
        if (!isIgnoring(activity)) { openExemptionScreen(activity); return }
        if (!OemGuide.hasGuide(oem())) {
            Toast.makeText(activity, "Already allowed to run in the background", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(activity)
            .setTitle("Battery restriction is off")
            .setMessage(OemGuide.guideMessage(oem()))
            .setPositiveButton("Open settings") { _, _ -> openOemScreen(activity) }
            .setNegativeButton("Close", null)
            .show()
    }
}
