package com.example.bluewave_mobile.ui.components

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.theme.BrandBlue
import com.example.bluewave_mobile.ui.theme.BrandBlueLight

/**
 * Gradient blue send button used by the chat input row.
 *
 * State machine:
 *  * **Idle (enabled)** — full brand-gradient fill, white arrow.
 *  * **Hovered** — gradient stays, button grows 2dp for affordance.
 *  * **Pressed** — gradient stays, button shrinks 2dp.
 *  * **Disabled** — flat surface-variant fill, faint arrow.
 *
 * The gradient itself is computed once per composition because
 * neither colour is recomposed during state changes (only the size
 * animates), so we don't pay the cost of re-allocating a [Brush] on
 * every press.
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

    val baseSize = 46.dp
    val targetSize = when {
        !enabled -> baseSize
        isPressed -> baseSize - 2.dp
        isHovered -> baseSize + 2.dp
        else -> baseSize
    }
    val animatedSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = tween(durationMillis = 120),
        label = "send-size",
    )

    val containerBrush: Brush = if (enabled) {
        Brush.linearGradient(colors = listOf(BrandBlue, BrandBlueLight))
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceVariant)
    }
    val iconTint: Color = if (enabled) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }

    val sendCd = stringResource(id = R.string.chat_send_cd)
    Box(
        modifier = modifier
            .size(animatedSize)
            .background(brush = containerBrush, shape = CircleShape)
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
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
    }
}
