package com.nfaalerts.collector.config

import android.content.pm.ApplicationInfo
import com.nfaalerts.collector.capture.RawTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSelectionRepositoryTest {
    @Test
    fun `eleventh selected package is rejected without changing persisted selections`() =
        runBlocking {
            val store = RecordingSelectionStore()
            val repository = SourceSelectionRepository(store)

            repeat(MAX_SELECTED_SOURCES) { index ->
                assertEquals(
                    SelectionUpdate.Accepted,
                    repository.upsert(selection("com.example.app$index")),
                )
            }

            assertEquals(
                SelectionUpdate.MaximumReached(MAX_SELECTED_SOURCES),
                repository.upsert(selection("com.example.eleventh")),
            )
            assertEquals(MAX_SELECTED_SOURCES, repository.snapshot().selections.size)
            assertEquals(MAX_SELECTED_SOURCES, store.lastSaved.size)
            assertFalse(store.lastSaved.any { it.packageName == "com.example.eleventh" })
        }

    @Test
    fun `accepted selections reload from the persistence contract`() =
        runBlocking {
            val store = RecordingSelectionStore()
            SourceSelectionRepository(store).upsert(selection("com.example.persisted"))

            val reloaded = SourceSelectionRepository(store)
            reloaded.load()

            assertEquals(setOf("com.example.persisted"), reloaded.snapshot().packageNames)
            assertEquals(
                RawTextField.DEFAULT_ORDER,
                reloaded
                    .snapshot()
                    .selections
                    .single()
                    .rawTextOrder,
            )
        }

    @Test
    fun `disabled selected packages remain persisted but leave the listener allowlist`() =
        runBlocking {
            val store = RecordingSelectionStore()
            val repository = SourceSelectionRepository(store)
            repository.upsert(selection("com.example.disabled").copy(enabled = false))
            repository.upsert(selection("com.example.enabled"))

            assertEquals(2, repository.snapshot().selections.size)
            assertTrue("com.example.disabled" in repository.snapshot().packageNames)
            assertEquals(null, repository.snapshot().sourceFor("com.example.disabled"))
            assertEquals("com.example.enabled", repository.snapshot().sourceFor("com.example.enabled")?.packageName)
            assertEquals(2, store.lastSaved.size)
        }

    @Test
    fun `BNN mapping requires explicit confirmation`() =
        runBlocking {
            val repository = SourceSelectionRepository(RecordingSelectionStore())

            val result =
                repository.upsert(
                    selection("com.example.bnn").copy(sourceId = "bnn", bnnMappingConfirmed = false),
                )

            assertEquals(SelectionUpdate.BnnConfirmationRequired, result)
            assertTrue(repository.snapshot().selections.isEmpty())
        }

    @Test
    fun `invalid source is rejected without changing persisted selections`() =
        runBlocking {
            val store = RecordingSelectionStore()
            val repository = SourceSelectionRepository(store)

            val result = repository.upsert(selection("com.example.invalid").copy(sourceId = ""))

            assertEquals(SelectionUpdate.InvalidSource("INVALID_SOURCE"), result)
            assertTrue(repository.snapshot().selections.isEmpty())
            assertTrue(store.lastSaved.isEmpty())
        }

    @Test
    fun `persistence rejection is returned without publishing rejected source`() =
        runBlocking {
            val store =
                RecordingSelectionStore(saveFailure = InvalidSelectionConfigException("DUPLICATE_SOURCE_PACKAGE"))
            val repository = SourceSelectionRepository(store)

            val result = repository.upsert(selection("com.example.duplicate"))

            assertEquals(SelectionUpdate.DuplicateSource, result)
            assertTrue(repository.snapshot().selections.isEmpty())
        }

    @Test
    fun `selection state flow publishes only explicitly confirmed BNN mapping`() =
        runBlocking {
            val repository = SourceSelectionRepository(RecordingSelectionStore())
            val unconfirmed = selection("com.example.bnn").copy(sourceId = "bnn")

            assertEquals(SelectionUpdate.BnnConfirmationRequired, repository.upsert(unconfirmed))
            assertTrue(
                repository.selections.value.selections
                    .isEmpty(),
            )
            assertEquals(
                SelectionUpdate.Accepted,
                repository.upsert(unconfirmed.copy(bnnMappingConfirmed = true)),
            )
            assertEquals(
                "com.example.bnn",
                repository.selections.value.selections
                    .single()
                    .packageName,
            )
        }

    @Test
    fun `invalid persisted source count fails closed with empty allowlist`() =
        runBlocking {
            val store = RecordingSelectionStore()
            store.lastSaved = (0..MAX_SELECTED_SOURCES).map { selection("com.example.invalid$it") }
            val repository = SourceSelectionRepository(store)

            repository.load()

            assertTrue(repository.snapshot().selections.isEmpty())
            assertEquals(SelectionLoadState.Invalid("TOO_MANY_SOURCES"), repository.loadState.value)
        }

    @Test
    fun `system classification includes updated system applications`() {
        assertTrue(InstalledAppClassifier.isSystem(ApplicationInfo.FLAG_SYSTEM))
        assertTrue(InstalledAppClassifier.isSystem(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
        assertTrue(
            InstalledAppClassifier.isSystem(
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
            ),
        )
        assertFalse(InstalledAppClassifier.isSystem(0))
    }

    @Test
    fun `hidden selected system apps remain visible`() {
        val apps =
            listOf(
                InstalledApp("com.example.user", "User", 0),
                InstalledApp("com.example.system", "System", ApplicationInfo.FLAG_SYSTEM),
                InstalledApp(
                    "com.example.updated",
                    "Updated",
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                ),
            )

        val visible =
            InstalledAppClassifier.visibleApps(
                apps = apps,
                showSystemApps = false,
                selectedPackages = setOf("com.example.updated"),
            )

        assertEquals(listOf("com.example.updated", "com.example.user"), visible.map { it.packageName })
    }

    @Test
    fun `installed apps search by label or package and remain label sorted`() {
        val apps =
            listOf(
                InstalledApp("com.fire.zulu", "Zulu Alarm", 0),
                InstalledApp("com.alert.alpha", "Alpha", 0),
                InstalledApp("com.weather.other", "Weather", 0),
            )

        assertEquals(
            listOf("com.alert.alpha", "com.fire.zulu"),
            InstalledAppClassifier
                .visibleApps(
                    apps = apps,
                    showSystemApps = true,
                    selectedPackages = emptySet(),
                    query = "AL",
                ).map(InstalledApp::packageName),
        )
    }

    private fun selection(packageName: String) =
        SourceSelection(
            packageName = packageName,
            appLabel = packageName,
            sourceId = "source-${packageName.substringAfterLast('.')}".lowercase(),
            enabled = true,
            bnnMappingConfirmed = false,
            rawTextOrder = RawTextField.DEFAULT_ORDER,
        )

    private class RecordingSelectionStore(
        private val saveFailure: RuntimeException? = null,
    ) : SourceSelectionStore {
        var lastSaved: List<SourceSelection> = emptyList()

        override suspend fun load(): List<SourceSelection> = lastSaved

        override suspend fun save(selections: List<SourceSelection>) {
            saveFailure?.let { throw it }
            lastSaved = selections.map { it.copy(rawTextOrder = it.rawTextOrder.toList()) }
        }
    }
}
