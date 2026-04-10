package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Save
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.theme.CppIde

/**
 * Top bar variant for the editor screen. Encapsulates the back button,
 * project name, dirty indicator, and save action.
 */
@Composable
fun EditorTopBar(
    projectName: String,
    activeFileName: String?,
    isDirty: Boolean,
    onBack: () -> Unit,
    onToggleDrawer: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CppTopBar(
        title = projectName,
        subtitle = activeFileName?.let { if (isDirty) "$it ●" else it },
        leading = {
            Row {
                CppIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
                CppIconButton(
                    icon = Icons.Outlined.Menu,
                    contentDescription = "Files",
                    onClick = onToggleDrawer,
                )
            }
        },
        trailing = {
            CppIconButton(
                icon = Icons.Outlined.Save,
                contentDescription = "Save",
                onClick = onSave,
                enabled = isDirty,
                tint = if (isDirty) CppIde.colors.accent else CppIde.colors.textDisabled,
            )
        },
        modifier = modifier,
    )
}
