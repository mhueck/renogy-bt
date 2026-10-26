package net.mhu.solstation.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [net.mhu.solstation.ui.main.MainScreen]. */
class MainScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        composeTestRule.setContent {
            MainScreen(
                permissionsGranted = true,
                onRequestPermissions = {}
            )
        }
    }

    @Test
    fun title_exists() {
        composeTestRule.onNodeWithText("SolStation Solar Monitor").assertExists()
    }
}
