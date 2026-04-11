package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cppide.core.project.ProjectNode
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.theme.CppIde

/**
 * Minimum touch-friendly height for each tree row. Previously rows were
 * padded only, which left < 24 dp tap targets — hard to hit reliably on
 * a phone.
 */
private val TreeRowMinHeight = 40.dp

/**
 * File extensions visible in the tree. Anything else — .pch, .o, .h
 * build droppings — is hidden to keep the drawer focused on source
 * and prose the user actually edits/reads. Exercise prompts land in
 * README.md files alongside solution.cpp, so both extensions live here.
 */
private fun ProjectNode.File.isVisible(): Boolean =
    name.endsWith(".cpp", ignoreCase = true) ||
        name.endsWith(".md", ignoreCase = true)

/**
 * Recursive tree row. Folders toggle expand on tap and also set themselves
 * as the "selected" parent for New File/New Folder actions. Files emit
 * [onFileClick] with their relative path. File rows surface rename/delete
 * buttons inline; folder rows surface an add-file button.
 */
@Composable
fun FileTreeNode(
    node: ProjectNode,
    depth: Int,
    activePath: String?,
    selectedFolder: String,
    onFileClick: (String) -> Unit,
    onFolderSelect: (String) -> Unit,
    onAddFileToFolder: (String) -> Unit,
    onRenameFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
) {
    val dimens = CppIde.dimens
    val indent = (dimens.spacingL.value * depth).dp

    when (node) {
        is ProjectNode.File -> {
            if (!node.isVisible()) return
            FileRow(
                node = node,
                indent = indent,
                isActive = node.relativePath == activePath,
                onClick = { onFileClick(node.relativePath) },
                onRename = { onRenameFile(node.relativePath) },
                onDelete = { onDeleteFile(node.relativePath) },
            )
        }
        is ProjectNode.Directory -> DirectoryRow(
            node = node,
            depth = depth,
            indent = indent,
            activePath = activePath,
            selectedFolder = selectedFolder,
            onFileClick = onFileClick,
            onFolderSelect = onFolderSelect,
            onAddFileToFolder = onAddFileToFolder,
            onRenameFile = onRenameFile,
            onDeleteFile = onDeleteFile,
        )
    }
}

@Composable
private fun FileRow(
    node: ProjectNode.File,
    indent: androidx.compose.ui.unit.Dp,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val bg = if (isActive) colors.editorSelection else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TreeRowMinHeight)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = indent + dimens.spacingS, end = dimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingS),
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            tint = if (isActive) colors.accent else colors.textSecondary,
            modifier = Modifier.size(dimens.iconSizeSmall),
        )
        BodyText(
            text = node.name,
            maxLines = 1,
            color = if (isActive) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        RowActionIcon(
            icon = Icons.Outlined.DriveFileRenameOutline,
            contentDescription = "Rename",
            onClick = onRename,
        )
        RowActionIcon(
            icon = Icons.Outlined.Delete,
            contentDescription = "Delete",
            onClick = onDelete,
            tint = colors.textSecondary,
        )
    }
}

@Composable
private fun DirectoryRow(
    node: ProjectNode.Directory,
    depth: Int,
    indent: androidx.compose.ui.unit.Dp,
    activePath: String?,
    selectedFolder: String,
    onFileClick: (String) -> Unit,
    onFolderSelect: (String) -> Unit,
    onAddFileToFolder: (String) -> Unit,
    onRenameFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    // Top-level directory (the project root) is always expanded.
    var expanded by remember(node.relativePath) { mutableStateOf(depth == 0) }
    val isSelectedParent = selectedFolder == node.relativePath
    val bg = if (isSelectedParent) colors.editorSelection else Color.Transparent

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TreeRowMinHeight)
                .background(bg)
                .clickable {
                    expanded = !expanded
                    onFolderSelect(node.relativePath)
                }
                .padding(start = indent + dimens.spacingS, end = dimens.spacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingS),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(dimens.iconSizeSmall),
            )
            BodyText(
                text = node.name,
                maxLines = 1,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            RowActionIcon(
                icon = Icons.Outlined.Add,
                contentDescription = "Add file to ${node.name}",
                onClick = { onAddFileToFolder(node.relativePath) },
            )
        }
        if (expanded) {
            for (child in node.children) {
                FileTreeNode(
                    node = child,
                    depth = depth + 1,
                    activePath = activePath,
                    selectedFolder = selectedFolder,
                    onFileClick = onFileClick,
                    onFolderSelect = onFolderSelect,
                    onAddFileToFolder = onAddFileToFolder,
                    onRenameFile = onRenameFile,
                    onDeleteFile = onDeleteFile,
                )
            }
        }
    }
}

@Composable
private fun RowActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = CppIde.colors.textSecondary,
) {
    val dimens = CppIde.dimens
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(dimens.iconSizeSmall),
        )
    }
}
