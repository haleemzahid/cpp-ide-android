package dev.cppide.ide.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact-density spacing tokens. Every component reads from these instead
 * of hardcoded `12.dp` values, so the entire app's information density can
 * be tuned in one place.
 *
 * Naming convention:
 *   spacingXxs (2)  spacingXs (4)  spacingS (6)  spacingM (8)
 *   spacingL (12)  spacingXl (16)  spacingXxl (24)  spacingXxxl (32)
 */
@Immutable
data class CppIdeDimensions(
    // ---- spacing scale ----
    val spacingXxs: Dp,
    val spacingXs: Dp,
    val spacingS: Dp,
    val spacingM: Dp,
    val spacingL: Dp,
    val spacingXl: Dp,
    val spacingXxl: Dp,
    val spacingXxxl: Dp,

    // ---- corner radii ----
    val radiusS: Dp,
    val radiusM: Dp,
    val radiusL: Dp,

    // ---- borders / dividers ----
    val borderHairline: Dp,
    val borderThick: Dp,

    // ---- touch targets ----
    val touchTargetMin: Dp,           // 48dp Material guideline minimum
    val iconButtonSize: Dp,
    val iconSize: Dp,
    val iconSizeSmall: Dp,

    // ---- chrome heights ----
    val topBarHeight: Dp,
    val statusBarHeight: Dp,
    val tabHeight: Dp,
    val gutterWidth: Dp,
    val drawerWidth: Dp,
    val fabSize: Dp,
)

/**
 * Default compact density. Smaller than Material defaults — every dp
 * matters on a phone screen.
 */
val CompactDimensions = CppIdeDimensions(
    spacingXxs = 2.dp,
    spacingXs = 4.dp,
    spacingS = 6.dp,
    spacingM = 8.dp,
    spacingL = 12.dp,
    spacingXl = 16.dp,
    spacingXxl = 24.dp,
    spacingXxxl = 32.dp,

    radiusS = 4.dp,
    radiusM = 6.dp,
    radiusL = 10.dp,

    borderHairline = 1.dp,
    borderThick = 2.dp,

    touchTargetMin = 44.dp,
    iconButtonSize = 36.dp,
    iconSize = 20.dp,
    iconSizeSmall = 16.dp,

    topBarHeight = 48.dp,
    statusBarHeight = 22.dp,
    tabHeight = 32.dp,
    gutterWidth = 44.dp,
    drawerWidth = 280.dp,
    fabSize = 52.dp,
)
