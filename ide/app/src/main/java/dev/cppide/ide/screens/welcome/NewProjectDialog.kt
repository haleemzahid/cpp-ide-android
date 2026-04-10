package dev.cppide.ide.screens.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppDialog
import dev.cppide.ide.components.CppTextField
import dev.cppide.ide.theme.CppIde

/**
 * Modal dialog for creating a new project. Validates the name (non-empty,
 * no path separators) before enabling the confirm button. Returns the
 * sanitised name to the caller.
 */
@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    val dimens = CppIde.dimens
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() &&
        trimmed.none { it == '/' || it == '\\' || it == ':' }

    CppDialog(
        title = "New project",
        onDismiss = onDismiss,
        confirmText = "Create",
        onConfirm = { if (valid) onCreate(trimmed) },
        confirmEnabled = valid,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spacingS)) {
            BodyText("Project name")
            CppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "hello-world",
            )
            CaptionText("A new folder is created under app-private storage with a starter main.cpp.")
        }
    }
}
