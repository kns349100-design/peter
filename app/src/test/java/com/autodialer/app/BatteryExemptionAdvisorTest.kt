package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test

class BatteryExemptionAdvisorTest {
    private class Store(var s: BatteryPromptState = BatteryPromptState()) : BatteryPromptStore { override fun load() = s; override fun save(s: BatteryPromptState) { this.s = s } }
    private var now = 1_000_000_000L
    private fun adv(oem: Oem, st: Store) = BatteryExemptionAdvisor(oem, st) { now }

    @Test fun `first time and not exempt - ask`() = assertEquals(BatteryAction.ASK_EXEMPTION, adv(Oem.OTHER, Store()).decide(false))
    @Test fun `exempt on a stock phone - nothing to do`() = assertEquals(BatteryAction.NONE, adv(Oem.OTHER, Store()).decide(true))
    @Test fun `after Not now it stays quiet for 24 hours then asks again`() {
        val st = Store(); val a = adv(Oem.OTHER, st); a.onDismissed()
        assertEquals(BatteryAction.NONE, a.decide(false))
        now += BatteryExemptionAdvisor.COOLDOWN_MS - 1; assertEquals(BatteryAction.NONE, a.decide(false))
        now += 2; assertEquals(BatteryAction.ASK_EXEMPTION, a.decide(false))
    }
    @Test fun `gives up after three refusals - never nags forever`() {
        val st = Store(); val a = adv(Oem.OTHER, st)
        repeat(BatteryExemptionAdvisor.MAX_DISMISSALS) { a.onDismissed(); now += BatteryExemptionAdvisor.COOLDOWN_MS + 1 }
        assertEquals(BatteryAction.NONE, a.decide(false))
    }
    @Test fun `granting resets the counters so a later revocation is asked about again`() {
        val st = Store(); val a = adv(Oem.OTHER, st)
        repeat(3) { a.onDismissed(); now += BatteryExemptionAdvisor.COOLDOWN_MS + 1 }
        assertEquals(BatteryAction.NONE, a.decide(true))                        // user allowed it in Settings
        assertEquals(0, st.s.dismissCount)
        assertEquals(BatteryAction.ASK_EXEMPTION, a.decide(false))              // OS update revoked it
    }
    @Test fun `OEM phones get the extra autostart guide exactly once after the exemption`() {
        val st = Store(); val a = adv(Oem.XIAOMI, st)
        assertEquals(BatteryAction.ASK_OEM_GUIDE, a.decide(true)); a.onOemGuideShown()
        assertEquals(BatteryAction.NONE, a.decide(true))
    }
    @Test fun `an OEM phone is still asked for the exemption first`() = assertEquals(BatteryAction.ASK_EXEMPTION, adv(Oem.SAMSUNG, Store()).decide(false))

    @Test fun `OEM detection`() {
        assertEquals(Oem.XIAOMI, OemDetector.detect("Xiaomi", "Redmi")); assertEquals(Oem.XIAOMI, OemDetector.detect("Xiaomi", "POCO"))
        assertEquals(Oem.SAMSUNG, OemDetector.detect("samsung", "samsung")); assertEquals(Oem.OPPO, OemDetector.detect("OPPO", "OPPO"))
        assertEquals(Oem.OPPO, OemDetector.detect("realme", "realme")); assertEquals(Oem.OPPO, OemDetector.detect("OnePlus", "OnePlus"))
        assertEquals(Oem.VIVO, OemDetector.detect("vivo", "vivo")); assertEquals(Oem.VIVO, OemDetector.detect("vivo", "iQOO"))
        assertEquals(Oem.HUAWEI, OemDetector.detect("HUAWEI", "HONOR")); assertEquals(Oem.OTHER, OemDetector.detect("Google", "google"))
        assertEquals(Oem.OTHER, OemDetector.detect(null, null))
    }
    @Test fun `every OEM has numbered steps and at least one settings target, OTHER has none`() {
        for (o in Oem.values()) {
            if (o == Oem.OTHER) { assertFalse(OemGuide.hasGuide(o)); assertTrue(OemGuide.targets(o).isEmpty()); continue }
            assertTrue("$o steps", OemGuide.steps(o).size >= 2); assertTrue("$o targets", OemGuide.targets(o).isNotEmpty())
            assertTrue(OemGuide.guideMessage(o).contains("1. ")); assertTrue(OemGuide.targets(o).all { it.pkg.contains('.') && it.cls.startsWith(it.pkg.substringBeforeLast('.').substringBeforeLast('.')) || it.cls.contains('.') })
        }
    }
    @Test fun `the explanation tells the user to come back and press Start`() {
        for (o in Oem.values()) assertTrue(OemGuide.exemptionMessage(o).contains("press Start"))
    }
}
