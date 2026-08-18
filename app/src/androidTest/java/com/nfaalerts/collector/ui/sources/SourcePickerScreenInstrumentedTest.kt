package com.nfaalerts.collector.ui.sources

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.nfaalerts.collector.MainActivity
import com.nfaalerts.collector.capture.RawTextField
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.config.SourceSelectionStore
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

        repeat(10) { composeRule.onNodeWithTag("source-toggle-com.user.$it").performClick() }
        composeRule.waitUntil { repository.selections.value.selections.size == 10 }
        composeRule.onNodeWithText("10 / 10").assertIsDisplayed()
        composeRule.onNodeWithTag("source-toggle-com.user.10").performClick()
        composeRule.onNodeWithText("Maximum 10 sources").assertIsDisplayed()
        composeRule.onNodeWithText("10 / 10").assertIsDisplayed()
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
        icon = ColorDrawable(Color.RED),
    )

    private class MemoryStore : SourceSelectionStore {
        private var values = emptyList<SourceSelection>()

        override suspend fun load(): List<SourceSelection> = values

        override suspend fun save(selections: List<SourceSelection>) {
            values = selections.map { it.copy(rawTextOrder = RawTextField.DEFAULT_ORDER) }
        }
    }
}
