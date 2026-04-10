package dev.cppide.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import dev.cppide.ide.theme.CppIde

/**
 * Lightweight dialog scaffold. Provides the surface, border, and a
 * consistent footer button row. Body composable lives at the call site.
 */
@Composable
fun CppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String = "Cancel",
    content: @Composable () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusL)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(dimens.borderHairline, colors.border, shape)
                .padding(dimens.spacingXl),
            verticalArrangement = Arrangement.spacedBy(dimens.spacingL),
        ) {
            SectionText(title)
            content()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.spacingM, Alignment.End),
            ) {
                CppButton(
                    text = dismissText,
                    onClick = onDismiss,
                    style = CppButtonStyle.Ghost,
                )
                CppButton(
                    text = confirmText,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    style = CppButtonStyle.Primary,
                )
            }
        }
    }
}
