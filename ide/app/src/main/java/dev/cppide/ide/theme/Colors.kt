package dev.cppide.ide.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens. These are NEVER hex values inside components —
 * components read e.g. [editorBackground], [accent], [diagnosticError]
 * so we can re-skin the entire app from this file.
 *
 * Two presets are exported below: [VSCodeDarkPlus] (default dark) and
 * [VSCodeLight] (default light). Add new schemes as additional `val`s.
 */
@Immutable
data class CppIdeColors(
    // ---- structural surfaces ----
    val background: Color,            // app window background
    val surface: Color,               // panels, cards
    val surfaceElevated: Color,       // floating elements (FAB, dialogs)
    val sidebar: Color,               // file tree / left rail
    val statusBar: Color,             // bottom status bar
    val border: Color,                // hairline separators

    // ---- editor surfaces ----
    val editorBackground: Color,
    val editorGutter: Color,
    val editorLineNumber: Color,
    val editorActiveLine: Color,
    val editorSelection: Color,
    val editorCursor: Color,

    // ---- text ----
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val textOnAccent: Color,

    // ---- accents / actions ----
    val accent: Color,                // primary brand color (VSCode blue)
    val accentMuted: Color,           // hover / pressed
    val ripple: Color,                // touch feedback

    // ---- diagnostics ----
    val diagnosticError: Color,
    val diagnosticWarning: Color,
    val diagnosticInfo: Color,
    val diagnosticHint: Color,

    // ---- syntax highlighting ----
    val syntaxKeyword: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxComment: Color,
    val syntaxFunction: Color,
    val syntaxType: Color,
    val syntaxVariable: Color,
    val syntaxOperator: Color,

    // ---- debug ----
    val breakpoint: Color,
    val breakpointDisabled: Color,
    val currentDebugLine: Color,
) {
    /** Material 3 needs a [ColorScheme] under the hood; build one from our tokens. */
    fun toMaterialColorScheme(): ColorScheme {
        val base = if (background.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = textOnAccent,
            secondary = accent,
            onSecondary = textOnAccent,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = sidebar,
            onSurfaceVariant = textSecondary,
            error = diagnosticError,
            onError = textOnAccent,
            outline = border,
            outlineVariant = border,
        )
    }

    private fun Color.luminance(): Float = (red * 0.299f + green * 0.587f + blue * 0.114f)
}

// ============================================================================
// VSCode Dark+ — the default dark scheme. Colors lifted from the published
// theme so anyone coming from VSCode feels at home.
// ============================================================================
val VSCodeDarkPlus = CppIdeColors(
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF252526),
    surfaceElevated = Color(0xFF2D2D30),
    sidebar = Color(0xFF252526),
    statusBar = Color(0xFF007ACC),
    border = Color(0xFF3E3E42),

    editorBackground = Color(0xFF1E1E1E),
    editorGutter = Color(0xFF1E1E1E),
    editorLineNumber = Color(0xFF858585),
    editorActiveLine = Color(0x40404040),
    editorSelection = Color(0x66264F78),
    editorCursor = Color(0xFFAEAFAD),

    textPrimary = Color(0xFFD4D4D4),
    textSecondary = Color(0xFFCCCCCC),
    textDisabled = Color(0xFF6E6E6E),
    textOnAccent = Color(0xFFFFFFFF),

    accent = Color(0xFF007ACC),
    accentMuted = Color(0xFF1F6FB2),
    ripple = Color(0x33FFFFFF),

    diagnosticError = Color(0xFFF14C4C),
    diagnosticWarning = Color(0xFFCCA700),
    diagnosticInfo = Color(0xFF3794FF),
    diagnosticHint = Color(0xFF6796E6),

    syntaxKeyword = Color(0xFF569CD6),
    syntaxString = Color(0xFFCE9178),
    syntaxNumber = Color(0xFFB5CEA8),
    syntaxComment = Color(0xFF6A9955),
    syntaxFunction = Color(0xFFDCDCAA),
    syntaxType = Color(0xFF4EC9B0),
    syntaxVariable = Color(0xFF9CDCFE),
    syntaxOperator = Color(0xFFD4D4D4),

    breakpoint = Color(0xFFE51400),
    breakpointDisabled = Color(0xFF848484),
    currentDebugLine = Color(0x80FFD700),
)

// ============================================================================
// VSCode Light — paired light scheme that follows the OS when system theme
// is selected. Same token shape, different colors.
// ============================================================================
val VSCodeLight = CppIdeColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF3F3F3),
    surfaceElevated = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF3F3F3),
    statusBar = Color(0xFF007ACC),
    border = Color(0xFFE5E5E5),

    editorBackground = Color(0xFFFFFFFF),
    editorGutter = Color(0xFFFFFFFF),
    editorLineNumber = Color(0xFF237893),
    editorActiveLine = Color(0x10000000),
    editorSelection = Color(0x66ADD6FF),
    editorCursor = Color(0xFF000000),

    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF424242),
    textDisabled = Color(0xFF9E9E9E),
    textOnAccent = Color(0xFFFFFFFF),

    accent = Color(0xFF0066B8),
    accentMuted = Color(0xFF005A9E),
    ripple = Color(0x22000000),

    diagnosticError = Color(0xFFE51400),
    diagnosticWarning = Color(0xFFBF8803),
    diagnosticInfo = Color(0xFF1A85FF),
    diagnosticHint = Color(0xFF6796E6),

    syntaxKeyword = Color(0xFF0000FF),
    syntaxString = Color(0xFFA31515),
    syntaxNumber = Color(0xFF098658),
    syntaxComment = Color(0xFF008000),
    syntaxFunction = Color(0xFF795E26),
    syntaxType = Color(0xFF267F99),
    syntaxVariable = Color(0xFF001080),
    syntaxOperator = Color(0xFF000000),

    breakpoint = Color(0xFFE51400),
    breakpointDisabled = Color(0xFF848484),
    currentDebugLine = Color(0x40FFD700),
)
