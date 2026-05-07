package com.example.bluewave_mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for [TwoPaneLayout].
 *
 * The 600 dp breakpoint is the single source of truth that decides
 * whether the app shows a single-pane navigation flow or a list-detail
 * tablet layout. Assertions are kept deliberately coarse — we only
 * check what is *visible* on each side of the breakpoint, not the
 * pixel-perfect width of either pane.
 */
class TwoPaneLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun narrow_viewport_renders_only_primary_pane() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 360.dp, height = 800.dp)) {
                TwoPaneLayout(
                    primary = { Text("primary-pane") },
                    secondary = { Text("secondary-pane") },
                )
            }
        }

        composeTestRule.onNodeWithText("primary-pane").assertIsDisplayed()
        composeTestRule.onNodeWithText("secondary-pane").assertIsNotDisplayed()
    }

    @Test
    fun wide_viewport_renders_both_panes() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 800.dp, height = 1200.dp)) {
                TwoPaneLayout(
                    primary = { Text("primary-pane") },
                    secondary = { Text("secondary-pane") },
                )
            }
        }

        composeTestRule.onNodeWithText("primary-pane").assertIsDisplayed()
        composeTestRule.onNodeWithText("secondary-pane").assertIsDisplayed()
    }

    @Test
    fun viewport_exactly_at_breakpoint_renders_both_panes() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 600.dp, height = 800.dp)) {
                TwoPaneLayout(
                    primary = { Text("primary-pane") },
                    secondary = { Text("secondary-pane") },
                )
            }
        }

        composeTestRule.onNodeWithText("primary-pane").assertIsDisplayed()
        composeTestRule.onNodeWithText("secondary-pane").assertIsDisplayed()
    }
}
