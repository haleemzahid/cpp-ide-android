package dev.cppide.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.cppide.ide.theme.CppIde

/**
 * The single top bar used everywhere. Compose your leading/trailing slots
 * with [CppIconButton]s — keeps the bar itself dumb and the screens in
 * full control of what shows up.
 */
@Composable
fun CppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    // Outer Column owns the surface color so it draws behind the status
    // bar (edge-to-edge), while the inner Row is pushed below the system
    // status bar via windowInsetsPadding.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(dimens.topBarHeight)
                .padding(horizontal = dimens.spacingS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = dimens.spacingS),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = CppIde.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = CppIde.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
        CppHorizontalDivider()
    }
}
