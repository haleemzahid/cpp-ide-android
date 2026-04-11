package dev.cppide.ide.screens.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.cppide.core.session.RecentProject
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppCard
import dev.cppide.ide.components.CppDialog
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.theme.CppIde
import java.text.DateFormat
import java.util.Date

/**
 * One row in the recent-projects list. Small and dumb — the screen
 * passes pre-formatted state down. Delete asks for confirmation
 * before firing because deleting a project wipes the on-disk files
 * AND drops the recents entry in one shot; there is no undo.
 */
@Composable
fun RecentProjectCard(
    project: RecentProject,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CppIde.dimens
    var showConfirm by remember { mutableStateOf(false) }

    CppCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(horizontal = dimens.spacingL, vertical = dimens.spacingM),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = CppIde.colors.accent,
                modifier = Modifier.size(dimens.iconSize),
            )
            Column(modifier = Modifier.weight(1f)) {
                BodyText(text = project.displayName, maxLines = 1)
                CaptionText(
                    text = "${project.rootPath}  ·  ${formatRelative(project.lastOpenedAt)}",
                    maxLines = 1,
                )
            }
            CppIconButton(
                icon = if (project.pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (project.pinned) "Unpin" else "Pin",
                onClick = onTogglePin,
                tint = if (project.pinned) CppIde.colors.accent else CppIde.colors.textSecondary,
            )
            CppIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = "Delete",
                onClick = { showConfirm = true },
                tint = CppIde.colors.textSecondary,
            )
        }
    }

    if (showConfirm) {
        CppDialog(
            title = "Delete project?",
            onDismiss = { showConfirm = false },
            confirmText = "Delete",
            onConfirm = {
                showConfirm = false
                onDelete()
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CppIde.dimens.spacingS)) {
                BodyText("This will permanently delete ${project.displayName} and all its files.")
                CaptionText("This action cannot be undone.")
            }
        }
    }
}

private fun formatRelative(timestamp: Long): String {
    val deltaMs = System.currentTimeMillis() - timestamp
    val mins = deltaMs / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        mins < 60 * 24 * 7 -> "${mins / (60 * 24)}d ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(timestamp))
    }
}
