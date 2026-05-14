package com.example.bluewave_mobile.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.state.ChatMessage
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI tests for [MessageBubble].
 *
 * Three rendering paths must remain visually distinguishable:
 *  * a normal incoming bubble shows its plaintext;
 *  * a normal outgoing bubble shows its plaintext;
 *  * a corrupted bubble shows the warning row (`chat_corrupted_label`)
 *    and the supporting message (`chat_corrupted_message`).
 *
 * The visual distinction is critical because the corrupted bubble is
 * the user's only signal that a peer-authored message could not be
 * authenticated, and the security-indicator icon next to the
 * timestamp is intentionally subtle.
 */
class MessageBubbleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun outgoing_bubble_displays_plaintext() {
        composeTestRule.setContent {
            BlueWaveTheme {
                MessageBubble(
                    message = ChatMessage(
                        id = 1L,
                        text = "outgoing-payload",
                        isOutgoing = true,
                        timestamp = 0L,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("outgoing-payload").assertIsDisplayed()
    }

    @Test
    fun incoming_bubble_displays_plaintext() {
        composeTestRule.setContent {
            BlueWaveTheme {
                MessageBubble(
                    message = ChatMessage(
                        id = 1L,
                        text = "incoming-payload",
                        isOutgoing = false,
                        timestamp = 0L,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("incoming-payload").assertIsDisplayed()
    }

    @Test
    fun corrupted_bubble_shows_warning_label_and_message() {
        composeTestRule.setContent {
            BlueWaveTheme {
                MessageBubble(
                    message = ChatMessage(
                        id = 2L,
                        text = "",
                        isOutgoing = false,
                        timestamp = 0L,
                        isCorrupted = true,
                    ),
                )
            }
        }

        val corruptedLabel = resources.getString(R.string.chat_corrupted_label)
        val corruptedMessage = resources.getString(R.string.chat_corrupted_message)
        composeTestRule.onNodeWithText(corruptedLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(corruptedMessage).assertIsDisplayed()
    }
}
