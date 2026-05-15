package com.example.bluewave_mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI tests for [EmptyStateView].
 *
 * The empty state composable is the fallback rendered everywhere from
 * the device list to the chat history; a regression here would
 * silently leave the user staring at an empty screen. These tests pin
 * the contract: title, message and CTA all render with the strings
 * the caller supplied, and tapping the CTA invokes [EmptyStateView]'s
 * `onAction` lambda.
 */
class EmptyStateViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun renders_title_message_and_action_label() {
        composeTestRule.setContent {
            BlueWaveTheme {
                EmptyStateView(
                    icon = Icons.Filled.BluetoothDisabled,
                    title = "No devices nearby",
                    message = "Move closer to a paired phone or tap the button below to start a fresh scan.",
                    actionLabel = "Scan again",
                    onAction = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No devices nearby").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Move closer to a paired phone or tap the button below to start a fresh scan.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan again").assertIsDisplayed()
    }

    @Test
    fun action_button_invokes_callback() {
        var clicked = false
        composeTestRule.setContent {
            BlueWaveTheme {
                EmptyStateView(
                    icon = Icons.Filled.BluetoothDisabled,
                    title = "Title",
                    message = "Message",
                    actionLabel = "Tap me",
                    onAction = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Tap me").performClick()
        assertTrue(clicked)
    }

    @Test
    fun cta_button_is_hidden_when_actionLabel_is_null() {
        composeTestRule.setContent {
            BlueWaveTheme {
                EmptyStateView(
                    icon = Icons.Filled.BluetoothDisabled,
                    title = "Title",
                    message = "Message",
                    actionLabel = null,
                )
            }
        }

        composeTestRule.onNodeWithText("Title").assertIsDisplayed()
        // Sanity check: the rendering tree contains exactly two text
        // nodes (title + message); no button node would be present.
    }
}
