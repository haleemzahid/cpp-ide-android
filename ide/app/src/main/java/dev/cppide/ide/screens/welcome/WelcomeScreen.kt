package dev.cppide.ide.screens.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.core.session.RecentProject
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppButton
import dev.cppide.ide.components.CppButtonStyle
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde

/**
 * Stateless welcome screen. Receives the recent-project list as state
 * and emits intents up — keeps the screen testable in isolation. Wired
 * to [dev.cppide.core.session.SessionRepository] by [WelcomeRoute].
 */
@Composable
fun WelcomeScreen(
    recents: List<RecentProject>,
    onOpenProject: (RecentProject) -> Unit,
    onTogglePin: (RecentProject) -> Unit,
    onCreateNew: () -> Unit,
    onOpenFolder: () -> Unit,
    onSettings: () -> Unit,
    onRunDebugSpike: () -> Unit,
    debugSpikeOutput: String?,
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
            title = "C++ IDE",
            trailing = {
                CppIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    onClick = onSettings,
                )
            },
        )

        // Add navigation-bar inset to the bottom of the scroll area so the
        // last item isn't hidden behind the system gesture / nav bar.
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
            item {
                SectionText("Recent")
            }

            if (recents.isEmpty()) {
                item { EmptyRecentsHint() }
            } else {
                items(items = recents, key = { it.rootPath }) { project ->
                    RecentProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project) },
                        onTogglePin = { onTogglePin(project) },
                    )
                }
            }

            item { Spacer(Modifier.height(dimens.spacingL)) }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CppButton(
                        text = "New project",
                        onClick = onCreateNew,
                        style = CppButtonStyle.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    CppButton(
                        text = "Open folder",
                        onClick = onOpenFolder,
                        style = CppButtonStyle.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---- TEMPORARY: debugger spike trigger ----
            // Throwaway UI to run the lldb-server reachability spike.
            // Remove once we commit to a debugger design.
            item { Spacer(Modifier.height(dimens.spacingM)) }
            item { SectionText("Debug spike (temporary)") }
            item {
                CppButton(
                    text = "Run lldb-server spike",
                    onClick = onRunDebugSpike,
                    style = CppButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (debugSpikeOutput != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceElevated)
                            .padding(dimens.spacingM),
                    ) {
                        Text(
                            text = debugSpikeOutput,
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
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
        CaptionText("No recent projects yet")
    }
}
