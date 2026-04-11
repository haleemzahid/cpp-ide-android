package dev.cppide.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import dev.cppide.ide.theme.CppIde

/**
 * Single-line text input. Uses [BasicTextField] underneath rather than
 * Material's TextField so we get exact control over the visuals via our
 * tokens (Material's TextField is loud and elevated).
 */
@Composable
fun CppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    textStyle: TextStyle = CppIde.typography.bodyMedium,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusS)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background)
            .border(dimens.borderHairline, colors.border, shape)
            .padding(horizontal = dimens.spacingM, vertical = dimens.spacingM),
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(
                text = placeholder,
                style = textStyle.copy(color = colors.textDisabled),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle.copy(color = colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
            modifier = modifier.fillMaxWidth(),
        )
    }
}
