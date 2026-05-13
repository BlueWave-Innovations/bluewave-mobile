package com.example.bluewave_mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluewave_mobile.ui.theme.BrandBlue
import com.example.bluewave_mobile.ui.theme.brandGradient

// ──────────────────────────────────────────────────────────────────────────────
// Section header
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Small uppercase caption shown above a group of related rows
 * (e.g. "ВНЕШНИЙ ВИД" / "СИСТЕМА" on the settings screen). The
 * label intentionally uses [MaterialTheme.colorScheme.onSurfaceVariant]
 * so it reads as ambient metadata, not as a navigation target.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Settings group container
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Rounded white card that wraps a stack of [SettingsRow]s. Rows are
 * laid out vertically and separated by a 1dp divider inset from the
 * left so the leading-icon column stays clean.
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

/**
 * Horizontal hair-line used between adjacent rows inside a
 * [SettingsCard]. The line is indented by 64dp on the left so it
 * doesn't run under leading icons / avatars — a small detail that
 * really sells the "modern app" feel.
 */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 64.dp),
        thickness = 0.7.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    )
}

/**
 * Single tappable row used inside a [SettingsCard]. Leading icon is
 * rendered inside a tinted rounded square (matches the mockup's
 * Telegram-style aesthetic). Trailing slot can host anything —
 * arrow, switch, badge, etc.
 *
 * @param iconTint Background colour for the leading icon chip.
 * @param trailing Optional composable rendered at the row's right
 *                 edge. Defaults to a chevron-right when [onClick]
 *                 is non-null.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = BrandBlue,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowMod = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .pressScale()
            .clickable(role = Role.Button, onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Row(
        modifier = rowMod.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon = icon, background = iconTint.copy(alpha = 0.12f), tint = iconTint)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Rounded square slot for a leading icon. Used by [SettingsRow] and
 * also as the avatar fallback in chat-list rows when a peer hasn't
 * shared a real photo.
 */
@Composable
fun IconChip(
    icon: ImageVector,
    background: Color,
    tint: Color,
    size: Int = 36,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size((size - 16).dp),
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Avatars
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Initial-based avatar — gradient blue circle with the first letter
 * (or two) of [name] rendered in white. Used as a graceful fallback
 * when the peer has no `PROFILE_METADATA` avatar attached yet.
 */
@Composable
fun InitialAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 48,
    gradient: Brush = brandGradient(),
) {
    val initials = remember(name) { initialsFor(name) }
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size / 2.8).sp,
        )
    }
}

private fun initialsFor(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val parts = trimmed.split(" ", "\u00A0").filter { it.isNotBlank() }
    return when (parts.size) {
        0 -> "?"
        1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Press animation
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Subtle scale-down on press, used to give rows / buttons that
 * tactile "Telegram-feel" without overriding the platform ripple.
 * The factor is intentionally small (0.97) so it stays delightful
 * rather than gimmicky.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "press-scale",
    )
    return this.scale(scale)
}

