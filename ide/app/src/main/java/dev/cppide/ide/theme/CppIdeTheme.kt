package dev.cppide.ide.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Top-level theme wrapper. Wrap your activity content in [CppIdeTheme] to
 * make all design tokens available via the [CppIde] accessor object inside
 * any composable below.
 *
 * ```
 * setContent {
 *     CppIdeTheme {
 *         WelcomeScreen(...)
 *     }
 * }
 *
 * // Inside a composable:
 * Box(Modifier.background(CppIde.colors.surface)) {
 *     Text("hi", color = CppIde.colors.textPrimary, style = CppIde.typography.bodyMedium)
 * }
 * ```
 */
@Composable
fun CppIdeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    tokens: CppIdeTokens = defaultTokens(darkTheme),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCppIdeTokens provides tokens) {
        MaterialTheme(
            colorScheme = tokens.colors.toMaterialColorScheme(),
            typography = tokens.typography.toMaterialTypography(),
            content = content,
        )
    }
}

/** Default token bundle. Switch on [darkTheme] to choose color scheme; everything else stays. */
fun defaultTokens(darkTheme: Boolean): CppIdeTokens = CppIdeTokens(
    colors = if (darkTheme) VSCodeDarkPlus else VSCodeLight,
    dimens = CompactDimensions,
    typography = DefaultTypography,
)

/**
 * Composition-local holding the active token bundle. Components should
 * read via the [CppIde] accessor below, not this constant directly.
 */
val LocalCppIdeTokens = staticCompositionLocalOf<CppIdeTokens> {
    error("CppIdeTheme not provided in the composition")
}

/**
 * Single accessor object for ergonomic token access:
 *   `CppIde.colors.accent`, `CppIde.dimens.spacingM`, `CppIde.typography.code`.
 */
object CppIde {
    val colors: CppIdeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCppIdeTokens.current.colors

    val dimens: CppIdeDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalCppIdeTokens.current.dimens

    val typography: CppIdeTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalCppIdeTokens.current.typography
}
