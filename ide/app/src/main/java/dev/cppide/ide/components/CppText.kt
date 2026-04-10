package dev.cppide.ide.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.cppide.ide.theme.CppIde

/**
 * Convenience wrappers around Material3 [Text] that pre-fill the right
 * tokens for each role. The point is so screens never write
 * `style = MaterialTheme.typography.bodyMedium` — they just say [BodyText].
 */

@Composable
fun TitleText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CppIde.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text = text,
    modifier = modifier,
    color = color,
    style = CppIde.typography.titleLarge,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun SectionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CppIde.colors.textPrimary,
) = Text(
    text = text,
    modifier = modifier,
    color = color,
    style = CppIde.typography.titleMedium,
)

@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CppIde.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text = text,
    modifier = modifier,
    color = color,
    style = CppIde.typography.bodyMedium,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun CaptionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CppIde.colors.textSecondary,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text = text,
    modifier = modifier,
    color = color,
    style = CppIde.typography.bodySmall,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun CodeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CppIde.colors.textPrimary,
) = Text(
    text = text,
    modifier = modifier,
    color = color,
    style = CppIde.typography.code,
)
