package dev.cppide.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.cppide.ide.theme.CppIde

/**
 * Tap-only icon button — used in toolbars, list rows, FABs. Touch target
 * defaults to [CppIdeDimensions.iconButtonSize] which respects Material's
 * 48dp guideline while staying compact.
 */
@Composable
fun CppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    background: Color = Color.Transparent,
) {
    val dimens = CppIde.dimens
    val resolvedTint = tint ?: CppIde.colors.textPrimary
    val alpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .size(dimens.iconButtonSize)
            .clip(RoundedCornerShape(dimens.radiusS))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedTint.copy(alpha = alpha),
            modifier = Modifier.size(dimens.iconSize),
        )
    }
}
