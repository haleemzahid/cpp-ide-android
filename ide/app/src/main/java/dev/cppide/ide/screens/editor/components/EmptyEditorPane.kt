package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.theme.CppIde

/**
 * Placeholder shown when no file is open. If [loading] is true shows a
 * spinner instead of the "open a file" hint — used for the welcome→editor
 * transition so the first couple of seconds don't look like a broken
 * empty pane.
 */
@Composable
fun EmptyEditorPane(
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.editorBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.accent,
                modifier = Modifier.size(36.dp),
            )
            CaptionText(
                text = "Opening project…",
                modifier = Modifier.padding(top = dimens.spacingM),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = colors.textDisabled,
                modifier = Modifier.size(56.dp),
            )
            CaptionText(
                text = "Open a file from the drawer to start editing",
                modifier = Modifier.padding(top = dimens.spacingM),
            )
        }
    }
}
