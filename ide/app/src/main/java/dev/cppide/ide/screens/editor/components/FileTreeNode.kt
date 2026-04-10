package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
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
 * Recursive tree row. Folders self-expand on tap; files emit [onFileClick]
 * with their relative path. The visual hierarchy is conveyed by horizontal
 * indent — keeping the row compact for phone screens.
 */
@Composable
fun FileTreeNode(
    node: ProjectNode,
    depth: Int,
    activePath: String?,
    onFileClick: (String) -> Unit,
) {
    val dimens = CppIde.dimens
    val indent = (dimens.spacingL.value * depth).dp

    when (node) {
        is ProjectNode.File -> FileRow(
            node = node,
            indent = indent,
            isActive = node.relativePath == activePath,
            onClick = { onFileClick(node.relativePath) },
        )
        is ProjectNode.Directory -> DirectoryRow(
            node = node,
            depth = depth,
            indent = indent,
            activePath = activePath,
            onFileClick = onFileClick,
        )
    }
}

@Composable
private fun FileRow(
    node: ProjectNode.File,
    indent: androidx.compose.ui.unit.Dp,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val bg = if (isActive) colors.editorSelection else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = indent + dimens.spacingS, end = dimens.spacingS, top = dimens.spacingXs, bottom = dimens.spacingXs),
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
        )
    }
}

@Composable
private fun DirectoryRow(
    node: ProjectNode.Directory,
    depth: Int,
    indent: androidx.compose.ui.unit.Dp,
    activePath: String?,
    onFileClick: (String) -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    // Top-level directory (the project root) is always expanded.
    var expanded by remember(node.relativePath) { mutableStateOf(depth == 0) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = indent + dimens.spacingS, end = dimens.spacingS, top = dimens.spacingXs, bottom = dimens.spacingXs),
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
            )
        }
        if (expanded) {
            for (child in node.children) {
                FileTreeNode(
                    node = child,
                    depth = depth + 1,
                    activePath = activePath,
                    onFileClick = onFileClick,
                )
            }
        }
    }
}
