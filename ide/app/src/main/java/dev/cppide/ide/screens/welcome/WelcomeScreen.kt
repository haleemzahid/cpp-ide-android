package dev.cppide.ide.screens.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cppide.core.session.RecentFile
import dev.cppide.core.session.RecentProject
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppButton
import dev.cppide.ide.components.CppButtonStyle
import dev.cppide.ide.components.CppCard
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde
import dev.cppide.ide.util.formatRelativeTime

@Composable
fun WelcomeScreen(
    recents: List<RecentProject>,
    recentFiles: List<RecentFile>,
    studentName: String?,
    isLoggedIn: Boolean,
    totalUnread: Int,
    isUploading: Boolean,
    uploadResult: String?,
    onOpenProject: (RecentProject) -> Unit,
    onOpenRecentFile: (RecentFile) -> Unit,
    onTogglePin: (RecentProject) -> Unit,
    onDeleteProject: (RecentProject) -> Unit,
    onCreateNew: () -> Unit,
    onOpenExercises: () -> Unit,
    onOpenQuestions: () -> Unit,
    onUploadSolutions: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        CppTopBar(
            title = if (studentName != null) "Hi, $studentName" else "C++ IDE",
            subtitle = if (!isLoggedIn) "Tap the login icon to sync progress & chat" else null,
            trailing = {
                Row {
                    BadgedBox(
                        badge = {
                            if (totalUnread > 0) {
                                Badge { CaptionText("$totalUnread", color = CppIde.colors.textOnAccent) }
                            }
                        },
                    ) {
                        CppIconButton(
                            icon = Icons.Outlined.QuestionAnswer,
                            contentDescription = "My Questions",
                            onClick = onOpenQuestions,
                        )
                    }
                    CppIconButton(
                        icon = Icons.Outlined.Info,
                        contentDescription = "About",
                        onClick = onAbout,
                    )
                    if (isLoggedIn) {
                        CppIconButton(
                            icon = Icons.Outlined.Logout,
                            contentDescription = "Log out",
                            onClick = onLogout,
                        )
                    } else {
                        CppIconButton(
                            icon = Icons.AutoMirrored.Outlined.Login,
                            contentDescription = "Log in",
                            onClick = onLogin,
                        )
                    }
                }
            },
        )

        val navInsets = WindowInsets.navigationBars.asPaddingValues()
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = dimens.spacingL,
                end = dimens.spacingL,
                top = dimens.spacingL,
                bottom = dimens.spacingL + navInsets.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            // ---- Recent Files section ----
            if (recentFiles.isNotEmpty()) {
                item { SectionText("Recent Files") }
                items(items = recentFiles, key = { it.filePath }) { file ->
                    RecentFileCard(
                        file = file,
                        onClick = { onOpenRecentFile(file) },
                    )
                }
                item { Spacer(Modifier.height(dimens.spacingM)) }
            }

            // ---- Projects section ----
            item { SectionText("Projects") }

            if (recents.isEmpty()) {
                item { EmptyRecentsHint() }
            } else {
                items(items = recents, key = { it.rootPath }) { project ->
                    RecentProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project) },
                        onTogglePin = { onTogglePin(project) },
                        onDelete = { onDeleteProject(project) },
                    )
                }
            }

            item { Spacer(Modifier.height(dimens.spacingL)) }

            item {
                CppButton(
                    text = "New project",
                    onClick = onCreateNew,
                    style = CppButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                CppButton(
                    text = "Browse exercises",
                    onClick = onOpenExercises,
                    style = CppButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                CppButton(
                    text = if (isUploading) "Uploading…" else "Upload my progress",
                    onClick = onUploadSolutions,
                    enabled = !isUploading,
                    style = CppButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (uploadResult != null) {
                item {
                    CaptionText(
                        text = uploadResult,
                        color = if (uploadResult.startsWith("Upload failed"))
                            colors.diagnosticError else colors.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentFileCard(
    file: RecentFile,
    onClick: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    CppCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            Icon(
                imageVector = Icons.Outlined.Code,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(dimens.iconSize),
            )
            Column(modifier = Modifier.weight(1f)) {
                BodyText(text = file.displayName, maxLines = 1)
                CaptionText(
                    text = "${file.projectName} · ${file.relativePath.substringAfterLast("/")} · ${formatRelativeTime(file.lastOpenedAt)}",
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyRecentsHint() {
    val dimens = CppIde.dimens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.spacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = CppIde.colors.textDisabled,
            modifier = Modifier.size(48.dp),
        )
        CaptionText("No projects yet")
    }
}

