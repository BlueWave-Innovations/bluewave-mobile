package com.example.bluewave_mobile.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────────────────────
// Brand palette
// ──────────────────────────────────────────────────────────────────────────────
//
// BlueWave's identity colour is a vivid, modern azure. The full design system
// is built around four "blue families" plus a neutral surface stack:
//
//  * `Brand*`        – primary accents, send buttons, bottom-nav active state.
//  * `BrandContainer*` – pale wash for chips, selected radio buttons, badges.
//  * `Accent*`       – secondary highlights (gradients, links, focus rings).
//  * `Surface*`      – page background, card background, divider lines.
//
// All values were tuned against the redesign mockup so the in-app screens read
// the same on both Pixel and Galaxy emulators.

val BrandBlue = Color(0xFF2E7BFF)
val BrandBlueDark = Color(0xFF1F60D8)
val BrandBlueLight = Color(0xFF5B97FF)

val BrandContainerLight = Color(0xFFE8F1FF)
val BrandContainerDark = Color(0xFF13243F)

val AccentCyan = Color(0xFF22B8E6)
val AccentIndigo = Color(0xFF6457FF)

val NeutralBackgroundLight = Color(0xFFF5F7FB)
val NeutralSurfaceLight = Color(0xFFFFFFFF)
val NeutralSurfaceVariantLight = Color(0xFFEEF1F6)
val NeutralOnSurfaceLight = Color(0xFF1A1F2E)
val NeutralOnSurfaceVariantLight = Color(0xFF6B7280)
val NeutralOutlineLight = Color(0xFFE2E6EE)

val NeutralBackgroundDark = Color(0xFF0A0E15)
val NeutralSurfaceDark = Color(0xFF141A24)
val NeutralSurfaceVariantDark = Color(0xFF1F2733)
val NeutralOnSurfaceDark = Color(0xFFE8ECF3)
val NeutralOnSurfaceVariantDark = Color(0xFF9AA3B2)
val NeutralOutlineDark = Color(0xFF2A3240)

val SuccessGreen = Color(0xFF22C55E)
val WarningAmber = Color(0xFFF59E0B)
val DangerRed = Color(0xFFEF4444)

// ──────────────────────────────────────────────────────────────────────────────
// Composite brushes
// ──────────────────────────────────────────────────────────────────────────────
//
// Gradient brushes are NOT theme colours per se — they're recomputed lazily
// inside composables that need them (e.g. the chat send button, the profile
// avatar ring). Keeping the factory functions here means callers don't
// hard-code raw hex codes in random call sites.

fun brandGradient(): Brush = Brush.linearGradient(
    colors = listOf(BrandBlue, BrandBlueLight),
)

fun avatarRingGradient(): Brush = Brush.sweepGradient(
    colors = listOf(BrandBlue, AccentCyan, AccentIndigo, BrandBlue),
)
