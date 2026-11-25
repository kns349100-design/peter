package com.autodialer.app

/** Phone makers whose system adds its OWN background-kill rules on top of stock Android's Doze. */
enum class Oem(val label: String) {
    XIAOMI("Xiaomi / Redmi / POCO"), SAMSUNG("Samsung"), OPPO("Oppo / Realme / OnePlus"),
    VIVO("Vivo / iQOO"), HUAWEI("Huawei / Honor"), OTHER("your phone")
}

object OemDetector {
    fun detect(manufacturer: String?, brand: String?): Oem {
        val m = (manufacturer ?: "").lowercase(); val b = (brand ?: "").lowercase()
        fun has(vararg k: String) = k.any { m.contains(it) || b.contains(it) }
        return when {
            has("xiaomi", "redmi", "poco") -> Oem.XIAOMI
            has("samsung") -> Oem.SAMSUNG
            has("oppo", "realme", "oneplus") -> Oem.OPPO
            has("vivo", "iqoo") -> Oem.VIVO
            has("huawei", "honor") -> Oem.HUAWEI
            else -> Oem.OTHER
        }
    }
}

data class OemTarget(val pkg: String, val cls: String)

/**
 * What to tell the user and where to send them. The component names are the ones commonly used by each maker's
 * settings app (see dontkillmyapp.com) but they CHANGE between ROM versions - so the Android side only launches
 * one that actually resolves on this phone, and otherwise falls back to the app's own settings page.
 */
object OemGuide {
    fun hasGuide(oem: Oem) = oem != Oem.OTHER

    fun steps(oem: Oem): List<String> = when (oem) {
        Oem.XIAOMI -> listOf("Turn ON \"Autostart\" for AutoDialer.", "Open App info > Battery saver and choose \"No restrictions\".", "In Recent apps, lock AutoDialer (long-press its card > lock icon).")
        Oem.SAMSUNG -> listOf("Open Settings > Battery > Background usage limits.", "Remove AutoDialer from \"Sleeping apps\" / \"Deep sleeping apps\".", "Add it to \"Never sleeping apps\".")
        Oem.OPPO -> listOf("Turn ON \"Allow auto-launch\" (Startup manager) for AutoDialer.", "Open App info > Battery usage and turn ON \"Allow background activity\".", "In Recent apps, lock AutoDialer.")
        Oem.VIVO -> listOf("Open i Manager > App manager > Autostart and allow AutoDialer.", "Open Battery > Background power consumption and allow AutoDialer.", "In Recent apps, lock AutoDialer.")
        Oem.HUAWEI -> listOf("Open App launch and set AutoDialer to \"Manage manually\".", "Turn ON Auto-launch, Secondary launch and Run in background.")
        Oem.OTHER -> emptyList()
    }

    fun targets(oem: Oem): List<OemTarget> = when (oem) {
        Oem.XIAOMI -> listOf(OemTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        Oem.SAMSUNG -> listOf(OemTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            OemTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"))
        Oem.OPPO -> listOf(OemTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            OemTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            OemTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
        Oem.VIVO -> listOf(OemTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            OemTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
        Oem.HUAWEI -> listOf(OemTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            OemTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
        Oem.OTHER -> emptyList()
    }

    fun exemptionMessage(oem: Oem): String =
        "A dialing session can run for a long time with the screen off. " +
            (if (oem == Oem.OTHER) "Some phones" else "${oem.label} phones") +
            " stop apps in the background to save battery, which can cut a session off in the middle of a call. " +
            "Allow AutoDialer to run without battery restrictions. Then come back here and press Start."

    fun guideMessage(oem: Oem): String =
        "Battery restriction is off - good. ${oem.label} phones have one more setting that can still close the app:\n\n" +
            steps(oem).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
}

data class BatteryPromptState(val dismissCount: Int = 0, val lastDismissedAt: Long = 0L, val oemGuideShown: Boolean = false)

interface BatteryPromptStore { fun load(): BatteryPromptState; fun save(s: BatteryPromptState) }

enum class BatteryAction { NONE, ASK_EXEMPTION, ASK_OEM_GUIDE }

/**
 * Decides WHEN to bother the user - at the moment a session is started (that is when it matters), not once at
 * app launch. It must never nag: after "Not now" it waits 24 hours and gives up after 3 refusals (the Settings
 * screen always stays available). If the exemption is later revoked (OS update, user change) the counter has
 * been reset by the earlier grant, so the user is asked again.
 */
class BatteryExemptionAdvisor(
    private val oem: Oem,
    private val store: BatteryPromptStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val COOLDOWN_MS = 24L * 60 * 60 * 1000
        const val MAX_DISMISSALS = 3
    }

    fun decide(isIgnoring: Boolean): BatteryAction {
        var s = store.load()
        if (isIgnoring) {
            if (s.dismissCount != 0 || s.lastDismissedAt != 0L) { s = s.copy(dismissCount = 0, lastDismissedAt = 0L); store.save(s) }
            return if (OemGuide.hasGuide(oem) && !s.oemGuideShown) BatteryAction.ASK_OEM_GUIDE else BatteryAction.NONE
        }
        if (s.dismissCount >= MAX_DISMISSALS) return BatteryAction.NONE
        if (s.lastDismissedAt > 0 && clock() - s.lastDismissedAt < COOLDOWN_MS) return BatteryAction.NONE
        return BatteryAction.ASK_EXEMPTION
    }

    fun onDismissed() { val s = store.load(); store.save(s.copy(dismissCount = s.dismissCount + 1, lastDismissedAt = clock())) }
    fun onOemGuideShown() { store.save(store.load().copy(oemGuideShown = true)) }
}
