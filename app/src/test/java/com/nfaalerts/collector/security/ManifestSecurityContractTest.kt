package com.nfaalerts.collector.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class ManifestSecurityContractTest {
    @Test
    fun manifestAndBackupDocumentsMatchTheSecurityContract() {
        val mainManifest = required("src/main/AndroidManifest.xml")
        val debugManifest = required("src/debug/AndroidManifest.xml")
        val backupRules = required("src/main/res/xml/backup_rules.xml")
        val extractionRules = required("src/main/res/xml/data_extraction_rules.xml")

        val main = parse(mainManifest)
        val application = main.documentElement.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(ANDROID_NS, "allowBackup"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(ANDROID_NS, "fullBackupContent"))
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttributeNS(ANDROID_NS, "dataExtractionRules"),
        )
        assertEquals("false", application.getAttributeNS(ANDROID_NS, "usesCleartextTraffic"))
        assertEquals("@drawable/ic_collector", application.getAttributeNS(ANDROID_NS, "icon"))
        required("src/main/res/drawable/ic_collector.xml")

        assertEquals(0, permissionCount(main, "android.permission.QUERY_ALL_PACKAGES"))
        assertEquals(1, permissionCount(parse(debugManifest), "android.permission.QUERY_ALL_PACKAGES"))

        assertLegacyRules(parse(backupRules))
        assertDataExtractionRules(parse(extractionRules))
    }

    private fun required(relativePath: String): String {
        val path = Path.of(relativePath)
        assertTrue("Missing security contract file: $relativePath", Files.exists(path))
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))

    private fun permissionCount(
        document: Document,
        permission: String,
    ): Int =
        (0 until document.documentElement.getElementsByTagName("uses-permission").length).count { index ->
            val node = document.documentElement.getElementsByTagName("uses-permission").item(index) as Element
            node.getAttributeNS(ANDROID_NS, "name") == permission
        }

    private fun assertLegacyRules(document: Document) {
        assertEquals("full-backup-content", document.documentElement.tagName)
        assertExcludes(document.documentElement)
    }

    private fun assertDataExtractionRules(document: Document) {
        assertEquals("data-extraction-rules", document.documentElement.tagName)
        for (sectionName in listOf("cloud-backup", "device-transfer")) {
            val section = document.documentElement.getElementsByTagName(sectionName).item(0) as? Element
            assertNotNull("Missing $sectionName", section)
            assertExcludes(section!!)
        }
    }

    private fun assertExcludes(section: Element) {
        val expectedDomains = setOf("database", "sharedpref", "file", "root", "external")
        val actualDomains =
            (0 until section.getElementsByTagName("exclude").length)
                .map { index ->
                    val node = section.getElementsByTagName("exclude").item(index) as Element
                    node.getAttribute("domain")
                }.toSet()

        assertTrue("Missing required excludes", actualDomains.containsAll(expectedDomains))
        assertFalse("Unexpected exclusion domain", actualDomains.any { it !in expectedDomains })
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
