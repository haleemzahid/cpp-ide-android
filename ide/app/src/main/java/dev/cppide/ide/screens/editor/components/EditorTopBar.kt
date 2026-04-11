package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.theme.CppIde

/**
 * Top bar variant for the editor screen. Encapsulates the back button,
 * project name, dirty indicator, save, share, and — for `.md` files —
 * a preview/source toggle. Undo/redo are hidden for markdown since the
 * editor view isn't even mounted in preview mode.
 */
@Composable
fun EditorTopBar(
    projectName: String,
    activeFileName: String?,
    isDirty: Boolean,
    canShare: Boolean,
    isMarkdown: Boolean,
    markdownPreview: Boolean,
    onBack: () -> Unit,
    onToggleDrawer: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onToggleMarkdownPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inPreview = isMarkdown && markdownPreview

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
            Row {
                if (isMarkdown) {
                    // Toggle icon flips between "show preview" and
                    // "show source" — the icon always depicts the
                    // mode you'd GO TO, not the current one.
                    CppIconButton(
                        icon = if (markdownPreview)
                            Icons.Outlined.Code
                        else
                            Icons.Outlined.RemoveRedEye,
                        contentDescription = if (markdownPreview)
                            "View source"
                        else
                            "View preview",
                        onClick = onToggleMarkdownPreview,
                    )
                }
                if (!inPreview) {
                    // Undo / redo aren't dirty-tracked — sora-editor
                    // owns its own history, and there's no cheap
                    // reactive signal for "can undo?". Rendered
                    // always-enabled in source view; a tap with
                    // nothing to undo is a harmless no-op in sora.
                    CppIconButton(
                        icon = Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = "Undo",
                        onClick = onUndo,
                    )
                    CppIconButton(
                        icon = Icons.AutoMirrored.Outlined.Redo,
                        contentDescription = "Redo",
                        onClick = onRedo,
                    )
                }
                CppIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = "Share",
                    onClick = onShare,
                    enabled = canShare,
                    tint = if (canShare) CppIde.colors.textPrimary else CppIde.colors.textDisabled,
                )
                if (!inPreview) {
                    CppIconButton(
                        icon = Icons.Outlined.Save,
                        contentDescription = "Save",
                        onClick = onSave,
                        enabled = isDirty,
                        tint = if (isDirty) CppIde.colors.accent else CppIde.colors.textDisabled,
                    )
                }
            }
        },
        modifier = modifier,
    )
}
