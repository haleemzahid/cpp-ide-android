package dev.cppide.ide.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.ide.R

/**
 * Renders a single VSCode codicon by its codepoint. Codicons is the
 * MIT-licensed icon font that ships with VSCode itself — using it
 * gives the IDE the same visual vocabulary VSCode users already know.
 *
 * Font file: `res/font/codicon.ttf`, sourced from
 * https://github.com/microsoft/vscode-codicons (MIT). The full
 * codepoint mapping lives in that repo's `src/template/mapping.json`;
 * the constants in [Codicons] cover the ones we use today.
 */
private val CodiconFontFamily = FontFamily(
    Font(R.font.codicon, FontWeight.Normal),
)

/**
 * @param char the codicon's unicode codepoint as a Char (e.g. [Codicons.DEBUG_CONTINUE])
 * @param size icon size — applies to both the layout dp box and the font size
 * @param color tint
 */
@Composable
fun Codicon(
    char: Char,
    size: TextUnit = 18.sp,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    Text(
        text = char.toString(),
        modifier = modifier,
        color = color,
        style = TextStyle(
            fontFamily = CodiconFontFamily,
            fontSize = size,
            // Codicons are designed at 16px square — disable any extra
            // line spacing so the glyph centers correctly inside its
            // own box.
            lineHeight = size,
        ),
    )
}

/**
 * Codepoints for the codicons we use in the IDE. Add new ones as
 * needed — the full mapping is in the codicons repo (see the file
 * doc comment on this file).
 */
object Codicons {
    // Debug toolbar (VSCode's standard debug actions)
    const val DEBUG_CONTINUE = '\uEACF'
    const val DEBUG_PAUSE = '\uEAD1'
    const val DEBUG_RESTART = '\uEAD2'
    const val DEBUG_START = '\uEAD3'
    const val DEBUG_STEP_INTO = '\uEAD4'
    const val DEBUG_STEP_OUT = '\uEAD5'
    const val DEBUG_STEP_OVER = '\uEAD6'
    const val DEBUG_STOP = '\uEAD7'
    const val DEBUG = '\uEAD8'
    const val DEBUG_ALT = '\uEB91'

    // Run (no debug)
    const val PLAY = '\uEB2C'
    const val RUN_ALL = '\uEB9E'

    // Misc
    const val BUG = '\uEAAF'
    const val GRIPPER = '\uEB04'
}
