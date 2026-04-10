package dev.cppide.ide.screens.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.cppide.core.session.RecentProject
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppCard
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.theme.CppIde
import java.text.DateFormat
import java.util.Date

/**
 * One row in the recent-projects list. Composable is small and dumb —
 * the screen passes pre-formatted state down. Click hooks live at the
 * call site so this card never needs to know about navigation.
 */
@Composable
fun RecentProjectCard(
    project: RecentProject,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CppIde.dimens

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
