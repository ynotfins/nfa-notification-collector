package com.nfaalerts.collector.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CollectorHomeStateRestorationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectedDestinationRestoresAndTokenDialogDoesNotPersistAcrossStateRestore() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            CollectorHomeScreen(
                repository = FakeCollectorUiRepository(),
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule.onNodeWithText("Connection settings").assertIsDisplayed()
        composeRule.onNodeWithText("Enter token").performClick()
        composeRule.onNodeWithText("Bearer token").performTextInput("transient-secret")
        composeRule.runOnIdle {
            assertTrue(
                composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Connection settings").assertIsDisplayed()
        composeRule.onNodeWithText("Secure token entry").assertDoesNotExist()
        composeRule.onNodeWithText("transient-secret").assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(
                composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
            )
        }
    }
}
