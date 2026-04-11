package dev.cppide.ide.screens.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppDialog
import dev.cppide.ide.components.CppTextField
import dev.cppide.ide.theme.CppIde

/**
 * Modal dialog for creating a new project. Validates the name (non-empty,
 * no path separators) before enabling the confirm button. Auto-focuses the
 * text field on open so the keyboard comes up immediately.
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

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
                modifier = Modifier.focusRequester(focusRequester),
            )
            CaptionText("A new folder is created under app-private storage with a starter main.cpp.")
        }
    }
}
