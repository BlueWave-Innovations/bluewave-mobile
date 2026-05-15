package com.example.bluewave_mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reactive viewport metadata that can be observed by every adaptive
 * composable in the app, mirroring the role `MediaQuery` plays in
 * Flutter / web. Carries the *available* width and height in dp plus
 * a derived window-size class buckets matching the
 * [Material 3 window-size classes](https://m3.material.io/foundations/layout/applying-layout/window-size-classes).
 */
@Stable
data class AdaptiveWindowInfo(
    val widthDp: Dp,
    val heightDp: Dp,
) {
    /** True when the host viewport is wider than [TWO_PANE_BREAKPOINT]. */
    val isExpandedWidth: Boolean get() = widthDp >= TWO_PANE_BREAKPOINT

    companion object {
        /**
         * Width threshold at which [TwoPaneLayout] commits to the
         * side-by-side rendering. The 600 dp value matches the
         * Material 3 "medium" window-size class — the same boundary
         * Android uses to enable the multi-pane lists in its own
         * Settings app on tablets and unfolded foldables.
         */
        val TWO_PANE_BREAKPOINT: Dp = 600.dp
    }
}

/**
 * Two-pane layout that renders [primary] and [secondary] side-by-side
 * on wide viewports and stacks them — primary in front, secondary
 * hidden — on narrow viewports.
 *
 * Implemented on top of [BoxWithConstraints] so the breakpoint is
 * read from the *actual* incoming layout constraints (parent-relative
 * width, not the device-wide window width). That means the layout
 * does the right thing inside a navigation rail / detail pane that is
 * itself narrower than the screen, on foldables in their tabletop
 * posture, and in `@Preview`s where the available width is determined
 * by the preview frame rather than by the device's resources.
 *
 * The visual divider between the panes uses the standard
 * [MaterialTheme.colorScheme.outlineVariant] hairline so the layout
 * blends with the rest of Material 3 surfaces without extra theming.
 *
 * @param primary Primary pane (list / index). Always rendered.
 * @param secondary Detail pane. Only rendered in two-pane mode — pass
 *                  a no-op or empty placeholder for single-pane hosts
 *                  that already drive navigation through `NavHost`.
 * @param primaryFraction Fraction of the available width consumed by
 *                        [primary] in two-pane mode. Defaults to 0.4
 *                        (Material 3 list-detail recommendation).
 */
@Composable
fun TwoPaneLayout(
    primary: @Composable () -> Unit,
    secondary: @Composable (AdaptiveWindowInfo) -> Unit,
    modifier: Modifier = Modifier,
    primaryFraction: Float = 0.4f,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val window = AdaptiveWindowInfo(widthDp = maxWidth, heightDp = maxHeight)
        if (window.isExpandedWidth) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(primaryFraction)
                        .fillMaxSize(),
                ) { primary() }
                VerticalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                )
                Box(
                    modifier = Modifier
                        .weight(1f - primaryFraction)
                        .fillMaxSize(),
                ) { secondary(window) }
            }
        } else {
            // Single-pane mode: render the primary pane only and let
            // the host's NavHost manage the detail. The horizontal
            // divider is a no-op in this branch but emitted defensively
            // so accidental mixing of orientations does not produce a
            // visual seam between the bar and the content.
            Box(modifier = Modifier.fillMaxSize()) {
                primary()
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.dp,
                )
            }
        }
    }
}
