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
    fun legacySelectionFileMigratesAtomicallyWithoutLossAndIsRetiredAfterReopen() =
        runBlocking {
            val suffix = System.nanoTime()
            val canonicalName = "collector-migrated-$suffix.json"
            val legacyName = "collector-legacy-$suffix.json"
            val legacy = File(context.filesDir, legacyName)
            val canonical = File(context.filesDir, canonicalName)
            try {
                legacy.writeText(
                    """{"version":1,"sources":[{"appLabel":"Legacy","bnnMappingConfirmed":false,"enabled":true,"packageName":"com.example.legacy","rawTextOrder":["bigText","text"],"sourceId":"other"}]}""",
                )
                val store = JsonSourceSelectionStore(context, canonicalName, legacyName)

                val migrated = store.load()
                val reopened = JsonSourceSelectionStore(context, canonicalName, legacyName).load()

                assertEquals("com.example.legacy", migrated.single().packageName)
                assertEquals(migrated, reopened)
                assertTrue(canonical.exists())
                assertFalse(legacy.exists())
            } finally {
                canonical.delete()
                File(context.filesDir, "$canonicalName.bak").delete()
                legacy.delete()
            }
        }

    @Test
    fun savingSourcesPreservesExistingCanonicalNonSecretFieldsAndUnknownVersionBytes() =
        runBlocking {
            val suffix = System.nanoTime()
            val mergeName = "collector-merge-$suffix.json"
            val unknownName = "collector-untouched-$suffix.json"
            val blockedLegacyName = "collector-blocked-legacy-$suffix.json"
            val mergeFile = File(context.filesDir, mergeName)
            val unknownFile = File(context.filesDir, unknownName)
            val blockedLegacyFile = File(context.filesDir, blockedLegacyName)
            val original =
                """{"configVersion":1,"deviceId":"nfa-primary-phone","endpoint":{"url":"https://example.invalid","headers":["a","b"]},"retry":{"base":30},"futureSafe":{"nested":[1,true,null]},"sources":[]}"""
            val unknown = " { \"configVersion\" : 2, \"future\" : {\"bytes\": [3,2,1]} } "
            try {
                mergeFile.writeText(original)
                unknownFile.writeText(unknown)
                blockedLegacyFile.writeText("{\"version\":1,\"sources\":[]}")
                val before = Json.parseToJsonElement(original).jsonObject

                JsonSourceSelectionStore(context, mergeName).save(listOf(source("com.example.new")))
                val after = Json.parseToJsonElement(mergeFile.readText()).jsonObject
                val unknownResult =
                    runCatching {
                        JsonSourceSelectionStore(context, unknownName, blockedLegacyName).load()
                    }

                assertEquals(before.getValue("deviceId"), after.getValue("deviceId"))
                assertEquals(before.getValue("endpoint").toString(), after.getValue("endpoint").toString())
                assertEquals(before.getValue("retry").toString(), after.getValue("retry").toString())
                assertEquals(before.getValue("futureSafe").toString(), after.getValue("futureSafe").toString())
                assertEquals(
                    "com.example.new",
                    JsonSourceSelectionStore(context, mergeName).load().single().packageName,
                )
                assertTrue(unknownResult.exceptionOrNull() is InvalidSelectionConfigException)
                assertEquals(unknown, unknownFile.readText())
                assertTrue(blockedLegacyFile.exists())
            } finally {
                mergeFile.delete()
                File(context.filesDir, "$mergeName.bak").delete()
                unknownFile.delete()
                blockedLegacyFile.delete()
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
