package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Source-level guards for the security architecture. They fail the build when someone (including future us)
 * bypasses it: logs PII, adds a plaintext store, or uses weak crypto.
 */
class ArchitectureGuardTest {
    private val src = File(listOf("src/main/java/com/autodialer/app", "app/src/main/java/com/autodialer/app").first { File(it).exists() })
    private val files = src.listFiles()!!.filter { it.name.endsWith(".kt") }
    private fun offenders(regex: Regex, allowed: Set<String> = emptySet()) =
        files.filter { it.name !in allowed && regex.containsMatchIn(stripComments(it.readText())) }.map { it.name }
    private fun stripComments(s: String) = s.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `nothing logs except SafeLog (which scrubs PII)`() {
        assertEquals(emptyList<String>(), offenders(Regex("""android\.util\.Log|\bLog\.[dviwe]\(|\bprintln\(|printStackTrace|System\.(out|err)"""), setOf("SafeLog.kt")))
    }
    @Test fun `no plaintext SharedPreferences for app data - only SecurePrefs (legacy migration and the non-PII battery flag are allowed)`() {
        assertEquals(emptyList<String>(), offenders(Regex("""getSharedPreferences\("""), setOf("SecurePrefs.kt", "SessionStore.kt", "BatteryExemptionFlow.kt")))
        val battery = files.single { it.name == "BatteryExemptionFlow.kt" }.readText()
        assertFalse("battery prompt prefs must stay free of personal data", Regex("""phone|name|lead""", RegexOption.IGNORE_CASE).containsMatchIn(battery.substringAfter("class PrefsStore").substringBefore("private fun oem")))
    }
    @Test fun `only AES-GCM is used, and only in DataCipher`() {
        val uses = files.flatMap { f -> Regex("""Cipher\.getInstance\("([^"]+)"\)""").findAll(f.readText()).map { f.name to it.groupValues[1] }.toList() }
        assertTrue(uses.isNotEmpty()); assertTrue("weak/unknown mode: $uses", uses.all { it.second == "AES/GCM/NoPadding" && it.first == "DataCipher.kt" })
        assertEquals(emptyList<String>(), offenders(Regex("""\b(MD5|SHA1|DES|ECB|RC4|Random\(\)\.next)\b|MessageDigest\.getInstance\("(MD5|SHA-?1)"\)"""), setOf("DialJournal.kt")))
    }
    @Test fun `files are written only through the crash-safe encrypted helpers (CSV export is the one intentional exception)`() {
        assertEquals(emptyList<String>(), offenders(Regex("""FileOutputStream|\.writeText\(|\.writeBytes\(|openFileOutput|FileWriter"""), setOf("DurableFile.kt", "DialJournal.kt", "CallLogActivity.kt")))
        assertTrue(files.single { it.name == "CallLogActivity.kt" }.readText().contains("\"exports\""))
    }
    @Test fun `no world-readable modes, no cleartext URLs, no hard-coded secrets`() {
        assertEquals(emptyList<String>(), offenders(Regex("""MODE_WORLD_(READABLE|WRITEABLE)|"http://""")))
        assertEquals(emptyList<String>(), offenders(Regex("""(?i)(secret|password|api_?key)\s*=\s*"[A-Za-z0-9_\-]{12,}"""")))
    }
    @Test fun `every dial goes through the write-ahead journal first`() {
        val e = files.single { it.name == "CallEngine.kt" }.readText()
        val body = e.substringAfter("private fun placeCall")
        assertTrue(body.indexOf("journalDial(") in 0 until body.indexOf("activity.startActivity("))
        assertEquals("startActivity for a call may only exist in placeCall", 1, Regex("""startActivity\(intent\)""").findAll(e).count())
    }
    @Test fun `the engine never recurses into dial and always resolves through DialResolver`() {
        val e = files.single { it.name == "CallEngine.kt" }.readText()
        val dial = e.substringAfter("private fun dial(").substringBefore("private fun placeCall")
        assertTrue(dial.contains("DialResolver.resolve(")); assertEquals("dial() may re-enter itself only via the posted continuation", 1, Regex("""\bdial\(""").findAll(dial.substringAfter("DialStep.Yield")).count())
    }

    @Test fun `login is email-only - no phone-number identity is sent to the backend any more`() {
        assertEquals(emptyList<String>(), offenders(Regex("""&phone=|phoneNumber\(\)|action=(login|resetPin)""")))
        val auth = files.single { it.name == "AuthManager.kt" }.readText()
        assertTrue(auth.contains("&email=")); assertTrue("an old phone-only session must count as logged out", auth.contains("&& email().isNotEmpty()"))
    }
}
