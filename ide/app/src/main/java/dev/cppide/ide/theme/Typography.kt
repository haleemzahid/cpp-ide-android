package dev.cppide.ide.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography tokens. UI text uses the system sans-serif (Roboto on stock
 * Android, looks native everywhere); code text uses the system monospace.
 *
 * To swap to JetBrains Mono later: bundle the .ttf in res/font/ and
 * change [codeFamily] only — every code-rendering composable picks it up.
 */
@Immutable
data class CppIdeTypography(
    val uiFamily: FontFamily,
    val codeFamily: FontFamily,

    // ---- UI text styles ----
    val titleLarge: TextStyle,        // app bar titles, page headings
    val titleMedium: TextStyle,       // section headings
    val titleSmall: TextStyle,        // panel headings
    val bodyLarge: TextStyle,         // primary body text
    val bodyMedium: TextStyle,        // secondary body / list rows
    val bodySmall: TextStyle,         // captions, status bar
    val labelLarge: TextStyle,        // button text
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,        // tiny meta (timestamps, hints)

    // ---- code-specific styles ----
    val code: TextStyle,              // 13sp default editor / terminal
    val codeSmall: TextStyle,         // 12sp inline code
    val codeTiny: TextStyle,          // 11sp gutter line numbers
)

val DefaultTypography = run {
    val ui = FontFamily.SansSerif
    val mono = FontFamily.Monospace
    CppIdeTypography(
        uiFamily = ui,
        codeFamily = mono,

        titleLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 22.sp),
        titleMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        titleSmall = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),

        bodyLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),

        labelLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
        labelMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 15.sp),
        labelSmall = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp),

        code = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        codeSmall = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        codeTiny = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),
    )
}

/** Project tokens onto Material 3 [Typography] for components that need it. */
internal fun CppIdeTypography.toMaterialTypography(): Typography = Typography(
    titleLarge = titleLarge,
    titleMedium = titleMedium,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = labelLarge,
    labelMedium = labelMedium,
    labelSmall = labelSmall,
)
