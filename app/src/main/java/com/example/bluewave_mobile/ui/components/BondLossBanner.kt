package com.example.bluewave_mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Inline status banner that surfaces an Android-16 bond loss
 * (`ACTION_KEY_MISSING`) for the active peer.
 *
 * The banner appears between the [androidx.compose.material3.TopAppBar]
 * and the chat history. It uses the standard Material 3
 * `errorContainer` colour role, the universally-recognised "no Wi-Fi /
 * connection" icon, and a single-line message that maps to the
 * user-facing terminology in the corrupted-message UI.
 *
 * **Animations.** The banner animates in with a vertical *expand* and
 * *fade-in* paired with a complementary *shrink* and *fade-out* on
 * dismissal. Both transitions use a 220 ms easing curve — shorter than
 * the Material spec's `motionDurationMedium2` so the chat does not
 * feel "soggy" on every reconnect, but long enough that screen
 * readers can announce the live region (`liveRegion = Polite`)
 * without missing the change.
 *
 * **Auto-hide.** The composable itself is purely presentational — it
 * draws what [visible] tells it to draw. The auto-hide policy
 * (de-bouncing flickers, hiding after restore, etc.) lives at the
 * caller side; step 32 wires this through a dedicated `StateFlow` so
 * the banner does not flash for sub-second blips.
 *
 * Accessibility: a `LiveRegionMode.Polite` semantics node ensures
 * TalkBack speaks the connection status whenever it changes without
 * interrupting the user's current focus.
 */
@Composable
fun BondLossBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    message: String = "Connection lost — waiting for re-bond",
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(durationMillis = 220)) +
            fadeIn(animationSpec = tween(durationMillis = 220)),
        exit = shrinkVertically(animationSpec = tween(durationMillis = 220)) +
            fadeOut(animationSpec = tween(durationMillis = 220)),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = message
                },
        ) {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
