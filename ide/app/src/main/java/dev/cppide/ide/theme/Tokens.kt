package dev.cppide.ide.theme

import androidx.compose.runtime.Immutable

/**
 * The single source of truth for all visual styling. Components consume
 * tokens via the [CppIde] accessor object, never via hardcoded values.
 *
 * To restyle the entire app:
 *   1. Edit one of [Colors], [Dimensions], or [Typography]
 *   2. Or define a new instance and pass it to [CppIdeTheme]
 *
 * Tokens are immutable data classes so swapping themes is a single state
 * update — Compose recomposes everything that reads from them.
 */
@Immutable
data class CppIdeTokens(
    val colors: CppIdeColors,
    val dimens: CppIdeDimensions,
    val typography: CppIdeTypography,
)
