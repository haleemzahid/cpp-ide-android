package dev.cppide.ide.screens.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.cppide.ide.util.formatRelativeTime
import dev.cppide.ide.util.slugToTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RecentProjectCard(
    project: RecentProject,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CppIde.dimens
    val colors = CppIde.colors
    var showConfirm by remember { mutableStateOf(false) }

    var projectInfo by remember(project.rootPath) { mutableStateOf(ProjectInfo(emptyList(), 0, 0)) }
    LaunchedEffect(project.rootPath) {
        projectInfo = withContext(Dispatchers.IO) { scanProject(project.rootPath) }
    }

    CppCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(horizontal = dimens.spacingL, vertical = dimens.spacingM),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(dimens.iconSize),
            )
            Column(modifier = Modifier.weight(1f)) {
                BodyText(text = project.displayName, maxLines = 1)

                if (projectInfo.exercises.isNotEmpty()) {
                    CaptionText(
                        text = "${projectInfo.exercises.size} exercises · ${projectInfo.editedCount} edited · ${formatRelativeTime(project.lastOpenedAt)}",
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(dimens.spacingXs))
                    // Show first few exercise names.
                    val preview = projectInfo.exercises.take(3).joinToString(", ") { it.name }
                    val suffix = if (projectInfo.exercises.size > 3)
                        " +${projectInfo.exercises.size - 3} more" else ""
                    CaptionText(
                        text = preview + suffix,
                        maxLines = 1,
                        color = colors.textDisabled,
                    )
                } else {
                    CaptionText(
                        text = "${projectInfo.cppFileCount} file${if (projectInfo.cppFileCount != 1) "s" else ""} · ${formatRelativeTime(project.lastOpenedAt)}",
                        maxLines = 1,
                    )
                }
            }
            CppIconButton(
                icon = if (project.pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (project.pinned) "Unpin" else "Pin",
                onClick = onTogglePin,
                tint = if (project.pinned) colors.accent else colors.textSecondary,
            )
            CppIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = "Delete",
                onClick = { showConfirm = true },
                tint = colors.textSecondary,
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

private data class ExerciseInfo(
    val name: String,
    val hasEdits: Boolean,
)

private data class ProjectInfo(
    val exercises: List<ExerciseInfo>,
    val editedCount: Int,
    val cppFileCount: Int,
)

/**
 * Scan a project directory to find exercises (subfolders with solution.cpp).
 * Detects whether solution.cpp has been edited from the starter template
 * by checking if the content differs from the default #include template.
 */
private fun scanProject(rootPath: String): ProjectInfo {
    val root = File(rootPath)
    if (!root.exists()) return ProjectInfo(emptyList(), 0, 0)

    val exercises = mutableListOf<ExerciseInfo>()
    var totalCpp = 0

    for (child in root.listFiles().orEmpty().sortedBy { it.name }) {
        if (!child.isDirectory) {
            if (child.extension.equals("cpp", ignoreCase = true)) totalCpp++
            continue
        }
        val solution = File(child, "solution.cpp")
        val readme = File(child, "README.md")
        if (solution.exists() || readme.exists()) {
            val hasEdits = solution.exists() &&
                solution.length() > 0 &&
                !solution.readText().trim().startsWith("#include <iostream>\nusing namespace std;")
            val displayName = child.name.slugToTitle()
            exercises.add(ExerciseInfo(displayName, hasEdits))
            if (solution.exists()) totalCpp++
        }
    }

    return ProjectInfo(
        exercises = exercises,
        editedCount = exercises.count { it.hasEdits },
        cppFileCount = totalCpp,
    )
}

