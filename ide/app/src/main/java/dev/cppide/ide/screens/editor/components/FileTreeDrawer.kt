package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.cppide.core.project.ProjectNode
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppDialog
import dev.cppide.ide.components.CppHorizontalDivider
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTextField
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde

/**
 * Left drawer holding the project file tree. Stateless — receives the
 * tree to render and forwards file taps + create/rename/delete actions
 * to the caller.
 */
@Composable
fun FileTreeDrawer(
    projectName: String,
    tree: ProjectNode.Directory?,
    activePath: String?,
    chatUnreadPaths: Set<String>,
    onFileClick: (String) -> Unit,
    onCreateFile: (parent: String, name: String) -> Unit,
    onCreateDirectory: (parent: String, name: String) -> Unit,
    onDeleteFile: (relativePath: String) -> Unit,
    onRenameFile: (relativePath: String, newName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    var createFileDialog by remember { mutableStateOf(false) }
    var createFolderDialog by remember { mutableStateOf(false) }
    // Relative path of the folder the user last selected as the creation
    // parent. Empty string means project root.
    var selectedParent by remember { mutableStateOf("") }

    // File pending rename/delete, or null when no dialog is open.
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .width(dimens.drawerWidth)
            .fillMaxHeight()
            .background(colors.sidebar)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimens.spacingL,
                    end = dimens.spacingS,
                    top = dimens.spacingS,
                    bottom = dimens.spacingS,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionText(text = projectName, modifier = Modifier.weight(1f))
            CppIconButton(
                icon = Icons.AutoMirrored.Outlined.NoteAdd,
                contentDescription = "New file",
                onClick = { createFileDialog = true },
            )
            CppIconButton(
                icon = Icons.Outlined.CreateNewFolder,
                contentDescription = "New folder",
                onClick = { createFolderDialog = true },
            )
        }
        CppHorizontalDivider()

        if (tree == null) {
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxHeight()) {
            item {
                FileTreeNode(
                    node = tree,
                    depth = 0,
                    activePath = activePath,
                    selectedFolder = selectedParent,
                    chatUnreadPaths = chatUnreadPaths,
                    onFileClick = onFileClick,
                    onFolderSelect = { path -> selectedParent = path },
                    onAddFileToFolder = { folderPath ->
                        selectedParent = folderPath
                        createFileDialog = true
                    },
                    onRenameFile = { path -> renameTarget = path },
                    onDeleteFile = { path -> deleteTarget = path },
                )
            }
        }
    }

    if (createFileDialog) {
        NewItemDialog(
            title = "New file",
            parent = selectedParent,
            placeholder = "untitled.cpp",
            onDismiss = { createFileDialog = false },
            onConfirm = { name ->
                createFileDialog = false
                // Default to .cpp if the user didn't type an extension.
                val withExt = if (name.endsWith(".cpp", ignoreCase = true)) name
                else "$name.cpp"
                onCreateFile(selectedParent, withExt)
            },
        )
    }
    if (createFolderDialog) {
        NewItemDialog(
            title = "New folder",
            parent = selectedParent,
            placeholder = "src",
            onDismiss = { createFolderDialog = false },
            onConfirm = { name ->
                createFolderDialog = false
                onCreateDirectory(selectedParent, name)
            },
        )
    }

    renameTarget?.let { path ->
        RenameDialog(
            original = path.substringAfterLast('/'),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                val withExt = if (newName.endsWith(".cpp", ignoreCase = true)) newName
                else "$newName.cpp"
                onRenameFile(path, withExt)
            },
        )
    }

    deleteTarget?.let { path ->
        ConfirmDeleteDialog(
            fileName = path.substringAfterLast('/'),
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                onDeleteFile(path)
            },
        )
    }
}

@Composable
private fun NewItemDialog(
    title: String,
    parent: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val dimens = CppIde.dimens
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() &&
        trimmed.none { it == '/' || it == '\\' || it == ':' }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    CppDialog(
        title = title,
        onDismiss = onDismiss,
        confirmText = "Create",
        onConfirm = { if (valid) onConfirm(trimmed) },
        confirmEnabled = valid,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spacingS)) {
            BodyText("Parent: " + if (parent.isEmpty()) "(project root)" else parent)
            Spacer(Modifier.width(dimens.spacingXs))
            CppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = placeholder,
                modifier = Modifier.focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun RenameDialog(
    original: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val dimens = CppIde.dimens
    var name by remember { mutableStateOf(original) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() &&
        trimmed.none { it == '/' || it == '\\' || it == ':' } &&
        trimmed != original

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    CppDialog(
        title = "Rename file",
        onDismiss = onDismiss,
        confirmText = "Rename",
        onConfirm = { if (valid) onConfirm(trimmed) },
        confirmEnabled = valid,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spacingS)) {
            BodyText("New name")
            CppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = original,
                modifier = Modifier.focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val dimens = CppIde.dimens
    CppDialog(
        title = "Delete file?",
        onDismiss = onDismiss,
        confirmText = "Delete",
        onConfirm = onConfirm,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spacingS)) {
            BodyText("This will permanently delete $fileName.")
            CaptionText("This action cannot be undone.")
        }
    }
}
