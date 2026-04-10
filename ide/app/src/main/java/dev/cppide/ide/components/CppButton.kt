package dev.cppide.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.cppide.ide.theme.CppIde

/**
 * The single button primitive used everywhere in the app. Variant chosen
 * via [style]; styling tokens come from [CppIde] so a global re-skin
 * doesn't need to touch components.
 *
 * Intentionally tiny — no icon slots, no loading state. Compose those
 * concerns at the call site by wrapping in a Row.
 */
@Composable
fun CppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CppButtonStyle = CppButtonStyle.Primary,
    enabled: Boolean = true,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    val (bg, fg, borderColor) = when (style) {
        CppButtonStyle.Primary -> Triple(colors.accent, colors.textOnAccent, Color.Transparent)
        CppButtonStyle.Secondary -> Triple(colors.surface, colors.textPrimary, colors.border)
        CppButtonStyle.Ghost -> Triple(Color.Transparent, colors.textPrimary, Color.Transparent)
    }
    val alpha = if (enabled) 1f else 0.4f
    val shape = RoundedCornerShape(dimens.radiusS)

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = dimens.touchTargetMin)
            .clip(shape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(if (borderColor != Color.Transparent) Modifier.border(dimens.borderHairline, borderColor, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = dimens.spacingL, vertical = dimens.spacingM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = fg.copy(alpha = alpha),
            style = CppIde.typography.labelLarge,
        )
    }
}

enum class CppButtonStyle { Primary, Secondary, Ghost }
