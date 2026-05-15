package com.example.bluewave_mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Custom rounded-corner system. Material 3 defaults are too tight for
 * the messaging-first feel we're going for — `medium` is bumped to
 * 16dp so settings cards and message bubbles all share the same
 * rounded silhouette as the mockup.
 */
val BlueWaveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
