package com.example.bluewave_mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.bluewave_mobile.R

/**
 * Compose-styled send button with explicit hover / pressed / disabled
 * visual states.
 *
 * The plan calls this the "Styles API send button"; the upstream Compose
 * 1.11 ButtonStyles API is not yet stable, so this composable hand-rolls
 * the same effect with [MutableInteractionSource]:
 *
 *  * **Idle** — `primaryContainer` background, `onPrimaryContainer` icon.
 *  * **Hovered** — slightly lifted size, brighter container.
 *  * **Pressed** — `primary` background, scale slightly down for the
 *    classic Material affordance.
 *  * **Disabled** — `surfaceVariant` background, low-emphasis icon.
 *
 * All transitions are animated to keep the chat input row feeling
 * "tactile" while the user composes a message; durations stay at the
 * Material `motionDurationShort1` (100 ms) bracket so the affordance
 * is felt but never gets in the way of fast typists.
 *
 * Accessibility: the button is reported with [Role.Button] and a fixed
 * "Send message" content description so TalkBack always announces it
 * the same way regardless of the current visual state.
 */
@Composable
fun SendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val baseSize = 44.dp
    val targetSize = when {
        !enabled -> baseSize
        isPressed -> baseSize - 2.dp
        isHovered -> baseSize + 2.dp
        else -> baseSize
    }
    val animatedSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = tween(durationMillis = 100),
        label = "SendButtonSize",
    )

    val containerColor: Color = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        isPressed -> MaterialTheme.colorScheme.primary
        isHovered -> MaterialTheme.colorScheme.primaryContainer
            .copy(alpha = 0.92f)
            .compositeOver(MaterialTheme.colorScheme.primary)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(durationMillis = 100),
        label = "SendButtonContainerColor",
    )

    val iconTint: Color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        isPressed -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val animatedIconTint by animateColorAsState(
        targetValue = iconTint,
        animationSpec = tween(durationMillis = 100),
        label = "SendButtonIconTint",
    )

    val sendCd = stringResource(id = R.string.chat_send_cd)
    Box(
        modifier = modifier
            .size(animatedSize)
            .background(animatedContainerColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = baseSize / 2),
                enabled = enabled,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = sendCd
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            tint = animatedIconTint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Cheap two-color blend used for the hovered container colour. Compose
 * does not ship a public `compositeOver` helper for [Color] (only on
 * `Color.Companion.compositeOver(Color)` once `androidx.compose.ui:1.7+`
 * lands), so we inline the SRC_OVER formula here.
 */
private fun Color.compositeOver(background: Color): Color {
    val outAlpha = alpha + background.alpha * (1f - alpha)
    if (outAlpha == 0f) return Color.Transparent
    val outRed = (red * alpha + background.red * background.alpha * (1f - alpha)) / outAlpha
    val outGreen = (green * alpha + background.green * background.alpha * (1f - alpha)) / outAlpha
    val outBlue = (blue * alpha + background.blue * background.alpha * (1f - alpha)) / outAlpha
    return Color(red = outRed, green = outGreen, blue = outBlue, alpha = outAlpha)
}
