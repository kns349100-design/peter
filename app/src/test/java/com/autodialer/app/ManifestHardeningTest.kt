package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Locks the manifest hardening in: a later edit that weakens it fails the build. */
class ManifestHardeningTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun res(path: String): File = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(res("AndroidManifest.xml"))
    private val app = doc.getElementsByTagName("application").item(0) as Element
    private fun a(e: Element, n: String) = e.getAttributeNS(ns, n)
    private fun all(tag: String) = (0 until doc.getElementsByTagName(tag).length).map { doc.getElementsByTagName(tag).item(it) as Element }

    @Test fun `cloud backup and device transfer are off`() {
        assertEquals("false", a(app, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", a(app, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", a(app, "fullBackupContent"))
    }
    @Test fun `the extraction rules exclude every domain from both cloud backup and device transfer`() {
        val x = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(res("res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val sec = x.getElementsByTagName(section).item(0) as Element
            val excluded = (0 until sec.getElementsByTagName("exclude").length).map { (sec.getElementsByTagName("exclude").item(it) as Element).getAttribute("domain") }
            for (d in listOf("root", "file", "database", "sharedpref", "external")) assertTrue("$section excludes $d", d in excluded)
            assertEquals("$section has no include", 0, sec.getElementsByTagName("include").length)
        }
        val legacy = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(res("res/xml/backup_rules.xml"))
        assertEquals(0, legacy.getElementsByTagName("include").length)
    }
    @Test fun `no cleartext traffic and not debuggable`() {
        assertEquals("false", a(app, "usesCleartextTraffic")); assertNotEquals("true", a(app, "debuggable"))
    }
    @Test fun `every component states exported explicitly and only the launcher is exported`() {
        val comps = all("activity") + all("service") + all("receiver") + all("provider")
        assertTrue(comps.isNotEmpty())
        for (c in comps) {
            val exported = a(c, "exported"); assertTrue("${a(c, "name")} must declare exported", exported == "true" || exported == "false")
            if (c.getElementsByTagName("intent-filter").length == 0) assertEquals("${a(c, "name")} must not be exported", "false", exported)
        }
        assertEquals(1, comps.count { a(it, "exported") == "true" })
    }
    @Test fun `foreground service declares its type`() {
        val s = all("service").single { a(it, "name") == ".DialerService" }
        assertEquals("specialUse", a(s, "foregroundServiceType"))
    }
    @Test fun `the unused call-log permission is gone and the remaining permissions are the expected set`() {
        val perms = all("uses-permission").map { a(it, "name").removePrefix("android.permission.") }.toSet()
        assertFalse("READ_CALL_LOG is not used by any code and Play restricts it", "READ_CALL_LOG" in perms)
        val allowed = setOf("CALL_PHONE", "READ_PHONE_STATE", "INTERNET", "ACCESS_NETWORK_STATE", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", "WAKE_LOCK", "POST_NOTIFICATIONS")
        assertEquals("unexpected permission added: ${perms - allowed}", emptySet<String>(), perms - allowed)
    }
    @Test fun `the file provider shares only the exports folder, not the whole cache`() {
        val x = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(res("res/xml/file_paths.xml"))
        val paths = x.documentElement.childNodes; var n = 0
        for (i in 0 until paths.length) { val e = paths.item(i) as? Element ?: continue; n++; assertEquals("cache-path", e.tagName); assertEquals("exports/", e.getAttribute("path")) }
        assertEquals(1, n)
        val p = all("provider").single(); assertEquals("false", a(p, "exported")); assertEquals("true", a(p, "grantUriPermissions"))
    }
}
