package com.example.bluewave_mobile.ui.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LookaheadScope
import com.example.bluewave_mobile.BuildConfig

/**
 * Wraps [content] in a [LookaheadScope] so child composables can read
 * the lookahead pass for shared-element / animated layout transitions.
 *
 * In `release` builds the wrapper is a *transparent passthrough* — no
 * extra drawing, no extra modifier, no measurable overhead beyond the
 * single LookaheadScope node.
 *
 * In `debug` builds the wrapper *additionally* draws a dashed magenta
 * outline around the scope at draw-time, so engineers can see at a
 * glance which subtree currently participates in lookahead-driven
 * animations. This stands in for the Compose 1.11 preview
 * `LookaheadAnimationVisualDebugging` API (still under @Experimental
 * / @Preview annotations) and stays inert outside developer builds
 * thanks to the [BuildConfig.DEBUG] guard.
 */
@Composable
fun DebugLookaheadScope(
    modifier: Modifier = Modifier,
    content: @Composable LookaheadScope.() -> Unit,
) {
    LookaheadScope {
        Box(
            modifier = modifier.then(
                if (BuildConfig.DEBUG) Modifier.debugLookaheadOutline() else Modifier,
            ),
        ) {
            this@LookaheadScope.content()
        }
    }
}

/**
 * Modifier that draws a 2 dp magenta dashed border across the layout
 * bounds. Only meant to be applied inside [DebugLookaheadScope]; the
 * dashed pattern matches the hand-drawn convention used by the
 * Layout Inspector to highlight composables undergoing animation.
 */
private fun Modifier.debugLookaheadOutline(): Modifier = drawWithContent {
    drawContent()
    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
    drawRect(
        color = Color.Magenta,
        topLeft = Offset.Zero,
        size = Size(width = size.width, height = size.height),
        style = Stroke(width = 2f, pathEffect = dash),
    )
}
