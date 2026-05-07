package com.example.bluewave_mobile.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin unit tests for the [AdaptiveWindowInfo] data class.
 *
 * The adaptive layout decision is the single source of truth for
 * everything in the app that branches on form factor (two-pane
 * navigation, FlexBox vs LazyColumn fallbacks, etc.). A regression in
 * the breakpoint would silently move every tablet user back to the
 * single-pane layout, so these tests guard the boundary condition
 * exactly at 600 dp.
 */
class AdaptiveWindowInfoTest {

    @Test
    fun `narrow viewport is not expanded width`() {
        val info = AdaptiveWindowInfo(widthDp = 360.dp, heightDp = 800.dp)
        assertFalse(info.isExpandedWidth)
    }

    @Test
    fun `breakpoint exactly at threshold is expanded width`() {
        val info = AdaptiveWindowInfo(
            widthDp = AdaptiveWindowInfo.TWO_PANE_BREAKPOINT,
            heightDp = 800.dp,
        )
        assertTrue(info.isExpandedWidth)
    }

    @Test
    fun `wide tablet viewport is expanded width`() {
        val info = AdaptiveWindowInfo(widthDp = 840.dp, heightDp = 1180.dp)
        assertTrue(info.isExpandedWidth)
    }
}
