package com.nfaalerts.collector.ui.sources

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.core.graphics.drawable.toBitmap
import com.nfaalerts.collector.MainActivity
import com.nfaalerts.collector.capture.RawTextField
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.config.SourceSelectionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SourcePickerScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickerShowsUserAppsByDefaultAndRetainsSelectedSystemAppWhenHidden() {
        val repository = SourceSelectionRepository(MemoryStore())
        val apps =
            listOf(
                app("com.user.zulu", "Zulu User"),
                app("com.system.beta", "Beta System", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP),
                app("com.user.alpha", "Alpha User"),
            )
        setPicker(apps, repository)

        composeRule.onNodeWithText("Alpha User").assertIsDisplayed()
        composeRule.onNodeWithText("com.user.alpha").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Alpha User icon").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show system apps switch").assertIsDisplayed()
        composeRule.onNodeWithText("Beta System").assertDoesNotExist()
        val alphaY =
            composeRule
                .onNodeWithText("Alpha User")
                .fetchSemanticsNode()
                .positionInRoot.y
        val zuluY =
            composeRule
                .onNodeWithText("Zulu User")
                .fetchSemanticsNode()
                .positionInRoot.y
        assertTrue(alphaY < zuluY)

        composeRule.onNodeWithTag("show-system-apps").performClick()
        composeRule.onNodeWithText("Beta System").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Select Beta System source").assertIsDisplayed()
        composeRule.onNodeWithTag("source-toggle-com.system.beta").performClick()
        composeRule.waitUntil {
            repository.selections.value.packageNames
                .contains("com.system.beta")
        }
        composeRule.onNodeWithTag("show-system-apps").performClick()
        composeRule.onNodeWithText("Beta System").assertIsDisplayed()

        composeRule.onNodeWithTag("source-search").performTextInput("beta")
        composeRule.onNodeWithText("Beta System").assertIsDisplayed()
        composeRule.onNodeWithText("Alpha User").assertDoesNotExist()
    }

    @Test
    fun pickerAcceptsTenAndBlocksTheEleventhWithVisibleCount() {
        val repository = SourceSelectionRepository(MemoryStore())
        val apps = (0..10).map { app("com.user.$it", "User %02d".format(it)) }
        setPicker(apps, repository)

        repeat(10) {
            val tag = "source-toggle-com.user.$it"
            composeRule.onNodeWithTag("sources-list").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).performClick()
        }
        composeRule.waitUntil { repository.selections.value.selections.size == 10 }
        composeRule.onNodeWithTag("sources-list").performScrollToNode(hasText("10 / 10"))
        composeRule.onNodeWithText("10 / 10").assertIsDisplayed()
        composeRule.onNodeWithTag("sources-list").performScrollToNode(hasTestTag("source-toggle-com.user.10"))
        composeRule.onNodeWithTag("source-toggle-com.user.10").performClick()
        composeRule.onNodeWithTag("sources-list").performScrollToNode(hasText("Maximum 10 sources"))
        composeRule.onNodeWithText("Maximum 10 sources").assertIsDisplayed()
        composeRule.onNodeWithText("10 / 10").assertIsDisplayed()

        composeRule.onNodeWithTag("sources-list").performScrollToNode(hasTestTag("edit-source-com.user.0"))
        composeRule.onNodeWithTag("edit-source-com.user.0").performClick()
        composeRule.onNodeWithText("Save source").performClick()
        composeRule.onNodeWithText("Maximum 10 sources").assertDoesNotExist()
    }

    @Test
    fun bnnSelectionRequiresExplicitConfirmationBeforeItCounts() {
        val repository = SourceSelectionRepository(MemoryStore())
        setPicker(listOf(app("com.example.bnn", "BNN")), repository) { "bnn" }

        composeRule.onNodeWithTag("source-toggle-com.example.bnn").performClick()
        composeRule.onNodeWithText("Confirm BNN source").assertIsDisplayed()
        composeRule.onNodeWithText("0 / 10").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-bnn-source").performClick()
        composeRule.waitUntil {
            repository.selections.value.packageNames
                .contains("com.example.bnn")
        }
        composeRule.onNodeWithText("1 / 10").assertIsDisplayed()
    }

    @Test
    fun selectedEditorShowsPersistedOrderAndRejectedMutationStaysOpen() {
        val repository = SourceSelectionRepository(MemoryStore())
        runBlocking {
            repository.upsert(
                selection("com.user.alpha", "Alpha User").copy(
                    rawTextOrder =
                        listOf(
                            RawTextField.TICKER,
                            RawTextField.TEXT,
                            RawTextField.BIG_TEXT,
                            RawTextField.TEXT_LINES,
                        ),
                ),
            )
        }
        setPicker(listOf(app("com.user.alpha", "Alpha User")), repository)

        composeRule.onNodeWithText("Raw priority: ticker, text, bigText, textLines").assertIsDisplayed()
        composeRule.onNodeWithTag("edit-source-com.user.alpha").performClick()
        composeRule.onNodeWithTag("source-id-editor").performTextClearance()
        composeRule.onNodeWithText("Save source").performClick()

        composeRule
            .onNodeWithTag("source-editor-list")
            .performScrollToNode(hasText("Source is invalid. Check every field and raw-text priority."))
        composeRule.onNodeWithText("Source is invalid. Check every field and raw-text priority.").assertIsDisplayed()
        composeRule.onNodeWithText("Edit Alpha User").assertIsDisplayed()
    }

    @Test
    fun rawPriorityCanRemoveAddAndReorderOnlySupportedCandidates() {
        val repository = SourceSelectionRepository(MemoryStore())
        runBlocking { repository.upsert(selection("com.user.alpha", "Alpha User")) }
        setPicker(listOf(app("com.user.alpha", "Alpha User")), repository)

        composeRule.onNodeWithTag("edit-source-com.user.alpha").performClick()
        composeRule
            .onNodeWithTag("source-editor-list")
            .performScrollToNode(hasTestTag("raw-remove-textLines"))
        composeRule.onNodeWithTag("raw-remove-textLines").performClick()
        composeRule
            .onNodeWithTag("source-editor-list")
            .performScrollToNode(hasTestTag("raw-add-textLines"))
        composeRule.onNodeWithTag("raw-add-textLines").performClick()
        composeRule
            .onNodeWithTag("source-editor-list")
            .performScrollToNode(hasTestTag("raw-up-textLines"))
        composeRule.onNodeWithTag("raw-up-textLines").performClick()
        composeRule.onNodeWithText("Save source").assertIsDisplayed()
        composeRule.onNodeWithText("Save source").performClick()

        composeRule.waitUntil {
            repository.selections.value.selections
                .single()
                .rawTextOrder ==
                listOf(RawTextField.BIG_TEXT, RawTextField.TEXT, RawTextField.TEXT_LINES, RawTextField.TICKER)
        }
        composeRule.onNodeWithText("Raw priority: bigText, text, textLines, ticker").assertIsDisplayed()
    }

    @Test
    fun sourceEditorDialogSupportsLargeFontAndRowSpecificTalkBackLabels() {
        val repository = SourceSelectionRepository(MemoryStore())
        runBlocking {
            repository.upsert(
                selection("com.example.bnn", "BNN").copy(
                    sourceId = "bnn",
                    bnnMappingConfirmed = true,
                    rawTextOrder = listOf(RawTextField.TEXT, RawTextField.BIG_TEXT, RawTextField.TICKER),
                ),
            )
        }
        val apps = listOf(app("com.example.bnn", "BNN"))
        composeRule.activity.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, fontScale = 2f)) {
                SourcePickerScreen(
                    apps = apps,
                    repository = repository,
                    sourceIdForPackage = { "bnn" },
                )
            }
        }

        composeRule.waitUntil {
            repository.selections.value.packageNames
                .contains("com.example.bnn")
        }
        composeRule.onNodeWithTag("edit-source-com.example.bnn").performClick()
        composeRule.onNodeWithTag("source-editor-list").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Enabled switch for BNN").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("BNN confirmation switch for BNN").assertIsDisplayed()
        composeRule
            .onNodeWithTag("source-editor-list")
            .performScrollToNode(hasContentDescription("Add textLines raw-text candidate"))
        composeRule
            .onNodeWithContentDescription("Add textLines raw-text candidate")
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithTag("source-editor-list")
            .performScrollToNode(hasContentDescription("Remove bigText raw-text candidate"))
        composeRule.onNodeWithContentDescription("Remove bigText raw-text candidate").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Move bigText raw-text candidate up")
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithTag("source-editor-list")
            .performScrollToNode(hasContentDescription("Move bigText raw-text candidate down"))
        composeRule
            .onNodeWithContentDescription("Move bigText raw-text candidate down")
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithTag("source-editor-list")
            .performScrollToNode(hasContentDescription("Remove bigText raw-text candidate"))
        composeRule
            .onNodeWithContentDescription("Remove bigText raw-text candidate")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Save source").assertIsDisplayed()
    }

    private fun setPicker(
        apps: List<InstalledApp>,
        repository: SourceSelectionRepository,
        sourceIdForPackage: (String) -> String = { "other" },
    ) {
        composeRule.activity.setContent {
            SourcePickerScreen(
                apps = apps,
                repository = repository,
                sourceIdForPackage = sourceIdForPackage,
            )
        }
    }

    private fun app(
        packageName: String,
        label: String,
        flags: Int = 0,
    ) = InstalledApp(
        packageName = packageName,
        label = label,
        flags = flags,
        icon = ColorDrawable(Color.RED).toBitmap(32, 32).asImageBitmap(),
    )

    private fun selection(
        packageName: String,
        label: String,
    ) = SourceSelection(
        packageName = packageName,
        appLabel = label,
        sourceId = "other",
        enabled = true,
        bnnMappingConfirmed = false,
        rawTextOrder = RawTextField.DEFAULT_ORDER,
    )

    private class MemoryStore : SourceSelectionStore {
        private var values = emptyList<SourceSelection>()

        override suspend fun load(): List<SourceSelection> = values

        override suspend fun save(selections: List<SourceSelection>) {
            values = selections.map { it.copy(rawTextOrder = it.rawTextOrder.toList()) }
        }
    }
}
