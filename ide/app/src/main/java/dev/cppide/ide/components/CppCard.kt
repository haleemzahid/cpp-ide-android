package dev.cppide.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.cppide.ide.theme.CppIde

/**
 * A simple bordered container. Replaces Material's [Card] which is too
 * elevated/rounded for our compact aesthetic.
 *
 * Provide [onClick] to make it tappable; otherwise it's static.
 */
@Composable
fun CppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(CppIde.dimens.spacingL),
    content: @Composable () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusM)

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(dimens.borderHairline, colors.border, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(contentPadding),
    ) {
        content()
    }
}
