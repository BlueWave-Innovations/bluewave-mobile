package com.example.bluewave_mobile.ui.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview

/**
 * Multi-preview annotation that renders a composable in both Material 3
 * light and dark colour schemes side by side in Android Studio's
 * preview pane.
 *
 * Compose ships [`@PreviewLightDark`][androidx.compose.ui.tooling.preview.PreviewLightDark]
 * starting with Compose UI 1.6, but we intentionally redefine it here:
 *
 *  * **Lock-step naming** — calling sites in this codebase always
 *    refer to `@PreviewLightDark` from the `ui.preview` package, so
 *    the name does not collide with the upstream annotation if the
 *    compose-ui version is bumped later;
 *  * **Discoverability** — co-locating it with the other custom
 *    preview wrappers (window sizes, font scales) lets a reader find
 *    every preview tool in this project under a single package.
 *
 * Apply this annotation to any preview function that depends on the
 * Material 3 colour scheme — message bubbles, cards, banners, etc. —
 * to verify both palettes in one render.
 */
@Preview(
    name = "Light",
    group = "Theme",
    showBackground = true,
    backgroundColor = 0xFFFFFBFE,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Preview(
    name = "Dark",
    group = "Theme",
    showBackground = true,
    backgroundColor = 0xFF1C1B1F,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class PreviewLightDark

/**
 * Multi-preview annotation that renders a composable at the standard
 * Android font-scale buckets (1.0 / 1.3 / 1.5 / 2.0). Used to verify
 * dynamic-type support — chat bubbles, empty-state copy and the
 * device-card name field have to handle the 200 % bucket without
 * truncating or overlapping the timestamp.
 *
 * Skipping the 1.0 baseline preview is intentional: every other
 * preview function in the project already renders at 1.0 by default,
 * so re-rendering it here would just duplicate output without
 * surfacing a regression.
 */
@Preview(name = "FontScale 1.3", group = "Font", fontScale = 1.3f, showBackground = true)
@Preview(name = "FontScale 1.5", group = "Font", fontScale = 1.5f, showBackground = true)
@Preview(name = "FontScale 2.0", group = "Font", fontScale = 2.0f, showBackground = true)
annotation class PreviewFontScales

/**
 * Multi-preview annotation that exercises the adaptive layout at the
 * three Material 3 window-size class boundaries:
 *
 *  * **Compact** — Pixel 5 in portrait;
 *  * **Medium** — Pixel C portrait / unfolded foldable;
 *  * **Expanded** — Pixel C landscape (tablet-class width).
 *
 * Apply to any composable that branches on
 * `AdaptiveWindowInfo.isExpandedWidth` — `TwoPaneLayout`,
 * `DeviceListScreen`, `ChatScreen` — so a single preview call
 * surfaces every form-factor variant Android Studio supports.
 */
@Preview(name = "Phone", group = "Window", device = Devices.PIXEL_5, showSystemUi = true)
@Preview(name = "Foldable", group = "Window", device = Devices.FOLDABLE, showSystemUi = true)
@Preview(name = "Tablet", group = "Window", device = Devices.PIXEL_C, showSystemUi = true)
annotation class PreviewWindowSizes
