package com.nfaalerts.collector

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class AppShellSmokeTest {
    @Test
    fun identityAndReadinessShellArePresent() {
        val sourceRoot = Path.of("src", "main", "java", "com", "nfaalerts", "collector")
        val application = sourceRoot.resolve("NfaCollectorApp.kt")
        val activity = sourceRoot.resolve("MainActivity.kt")

        assertTrue("NfaCollectorApp shell is missing", Files.exists(application))
        assertTrue("MainActivity readiness shell is missing", Files.exists(activity))

        val applicationSource = String(Files.readAllBytes(application), StandardCharsets.UTF_8)
        val activitySource = String(Files.readAllBytes(activity), StandardCharsets.UTF_8)
        assertTrue(applicationSource.contains("class NfaCollectorApp"))
        assertTrue(activitySource.contains("NFA Notification Collector"))
        assertTrue(activitySource.contains("Readiness: setup required"))
    }
}
