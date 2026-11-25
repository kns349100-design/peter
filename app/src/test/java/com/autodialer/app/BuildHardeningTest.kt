package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Locks the release hardening in: weakening R8 or dropping a keep rule fails the build. */
class BuildHardeningTest {
    private fun f(p: String): File = listOf(File(p), File("app/$p")).first { it.exists() }
    private val gradle = f("build.gradle").readText()
    private val rules = f("proguard-rules.pro").readText()
    private val release = gradle.substringAfter("release {").substringBefore("}")

    @Test fun `release build is minified, obfuscated and resource-shrunk`() {
        assertTrue(Regex("""minifyEnabled\s+true""").containsMatchIn(release)); assertTrue(Regex("""shrinkResources\s+true""").containsMatchIn(release))
        assertTrue(release.contains("proguard-android-optimize.txt")); assertTrue(release.contains("proguard-rules.pro"))
        assertFalse(Regex("""debuggable\s+true""").containsMatchIn(release))
    }
    @Test fun `crash traces stay decodable`() { assertTrue(rules.contains("-keepattributes SourceFile,LineNumberTable")); assertTrue(rules.contains("-renamesourcefileattribute")) }
    @Test fun `debug logging is stripped from release`() {
        val block = rules.substringAfter("-assumenosideeffects class android.util.Log").substringBefore("}")
        for (m in listOf("v(", "d(", "i(")) assertTrue("Log.$m", block.contains(m)); assertFalse("warnings and errors must stay", block.contains(" w(") || block.contains(" e("))
    }
    @Test fun `enums that are written to disk by name are kept, so saved sessions survive an update`() {
        for (e in listOf("CallSequencer\$Status", "CallSequencer\$Phase", "JournalType", "OutcomeTag")) assertTrue("keep rule for $e", rules.contains("-keepclassmembers enum com.autodialer.app.$e"))
        val src = File(listOf("src/main/java/com/autodialer/app", "app/src/main/java/com/autodialer/app").first { File(it).exists() })
        val persisted = src.listFiles()!!.filter { it.name.endsWith(".kt") }.flatMap { Regex("""(\w+(?:\.\w+)*)\.valueOf\(""").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }.toSet()
        assertTrue("valueOf() on $persisted needs a keep rule", persisted.all { it.endsWith("Status") || it == "Phase" || it == "JournalType" || it == "OutcomeTag" || it.startsWith("Oem") })
    }
    @Test fun `Razorpay keep rules are present`() {
        for (r in listOf("-keep class com.razorpay.** { *; }", "-dontwarn com.razorpay.**", "@android.webkit.JavascriptInterface", "-keepattributes JavascriptInterface", "onPayment*")) assertTrue(r, rules.contains(r))
    }
}
