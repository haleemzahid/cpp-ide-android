package dev.cppide.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cppide.ide.theme.CppIde

/** Horizontal hairline border. */
@Composable
fun CppHorizontalDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(CppIde.dimens.borderHairline)
            .background(CppIde.colors.border),
    )
}

/** Vertical hairline border. */
@Composable
fun CppVerticalDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxHeight()
            .width(CppIde.dimens.borderHairline)
            .background(CppIde.colors.border),
    )
}
