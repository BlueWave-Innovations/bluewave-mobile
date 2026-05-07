package com.example.bluewave_mobile.ui.permissions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI tests for [PermissionGateView].
 *
 * The gate is the only thing that stands between the user and a
 * SecurityException from the radio API; any regression that hides the
 * CTA, drops the explanation copy or stops the FlowRow from rendering
 * the missing-permission chips would brick first-run discovery on
 * Android 12+. These tests pin the contract.
 */
class PermissionGateViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun renders_title_explanation_and_cta() {
        composeTestRule.setContent {
            BlueWaveTheme {
                PermissionGateView(missing = emptyList(), onGrantClick = {})
            }
        }

        val title = resources.getString(R.string.permission_title)
        val explanation = resources.getString(R.string.permission_message)
        val cta = resources.getString(R.string.permission_grant)
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        composeTestRule.onNodeWithText(explanation).assertIsDisplayed()
        composeTestRule.onNodeWithText(cta).assertIsDisplayed()
    }

    @Test
    fun missing_permission_chips_show_short_names() {
        composeTestRule.setContent {
            BlueWaveTheme {
                PermissionGateView(
                    missing = listOf(
                        "android.permission.BLUETOOTH_CONNECT",
                        "android.permission.BLUETOOTH_SCAN",
                    ),
                    onGrantClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("BLUETOOTH_CONNECT").assertIsDisplayed()
        composeTestRule.onNodeWithText("BLUETOOTH_SCAN").assertIsDisplayed()
    }

    @Test
    fun cta_invokes_onGrantClick() {
        var clicked = false
        composeTestRule.setContent {
            BlueWaveTheme {
                PermissionGateView(missing = emptyList(), onGrantClick = { clicked = true })
            }
        }

        val cta = resources.getString(R.string.permission_grant)
        composeTestRule.onNodeWithText(cta).performClick()
        assertTrue(clicked)
    }
}
