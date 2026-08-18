package com.nfaalerts.collector.config

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nfaalerts.collector.capture.NfaNotificationListenerService
import com.nfaalerts.collector.capture.NotificationAccessStatus
import com.nfaalerts.collector.capture.RawTextField
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConfigAndRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun canonicalCollectorConfigPersistsAndReopensSelections() =
        runBlocking {
            val fileName = "collector-config.json"
            try {
                val selection = source("com.example.persisted")
                JsonSourceSelectionStore(context).save(listOf(selection))

                val reopened = JsonSourceSelectionStore(context).load()
                val root = Json.parseToJsonElement(File(context.filesDir, fileName).readText()).jsonObject
                assertEquals("1", root.getValue("configVersion").jsonPrimitive.content)
                assertEquals(selection, reopened.single())
                assertTrue("sources" in root)
                assertFalse(File(context.filesDir, "collector-source-selection-v1.json").exists())
            } finally {
                File(context.filesDir, fileName).delete()
                File(context.filesDir, "$fileName.bak").delete()
            }
        }

    @Test
    fun invalidAndUnknownConfigVersionsFailClosed() =
        runBlocking {
            val invalidName = "collector-invalid-${System.nanoTime()}.json"
            val unknownName = "collector-unknown-${System.nanoTime()}.json"
            try {
                File(context.filesDir, invalidName).writeText("not-json")
                File(context.filesDir, unknownName).writeText("{\"configVersion\":2,\"sources\":[]}")
                val invalid = SourceSelectionRepository(JsonSourceSelectionStore(context, invalidName))
                val unknown = SourceSelectionRepository(JsonSourceSelectionStore(context, unknownName))

                invalid.load()
                unknown.load()

                assertTrue(invalid.snapshot().selections.isEmpty())
                assertTrue(unknown.snapshot().selections.isEmpty())
                assertEquals(SelectionLoadState.Invalid("INVALID_CONFIG"), invalid.loadState.value)
                assertEquals(SelectionLoadState.Invalid("UNKNOWN_CONFIG_VERSION"), unknown.loadState.value)
            } finally {
                File(context.filesDir, invalidName).delete()
                File(context.filesDir, unknownName).delete()
            }
        }

    @Test
    fun installedAppRepositoryReturnsActualLabelIconAndClassification() =
        runBlocking {
            val app =
                InstalledAppRepository(context.packageManager).installedApps().first {
                    it.packageName ==
                        context.packageName
                }
            val flags = context.applicationInfo.flags

            assertEquals("NFA Notification Collector", app.label)
            assertNotNull(app.icon)
            assertEquals(InstalledAppClassifier.isSystem(flags), app.isSystem)
        }

    @Test
    fun notificationAccessHelperMatchesApi36PlatformResult() {
        val component = ComponentName(context, NfaNotificationListenerService::class.java)
        val expected =
            context
                .getSystemService(
                    NotificationManager::class.java,
                ).isNotificationListenerAccessGranted(component)

        assertEquals(expected, NotificationAccessStatus.isGranted(context, component))
    }

    private fun source(packageName: String) =
        SourceSelection(
            packageName = packageName,
            appLabel = "Example",
            sourceId = "other",
            enabled = true,
            bnnMappingConfirmed = false,
            rawTextOrder = RawTextField.DEFAULT_ORDER,
        )
}
