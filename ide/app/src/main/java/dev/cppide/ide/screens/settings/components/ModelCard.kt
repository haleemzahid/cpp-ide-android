package dev.cppide.ide.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.cppide.core.ai.ModelDownloadState
import dev.cppide.core.ai.ModelInfo
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppButton
import dev.cppide.ide.components.CppButtonStyle
import dev.cppide.ide.components.CppCard
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde

/**
 * Stateless row for one downloadable model. Shows name, description,
 * current state (with progress bar when downloading), and the set of
 * actions valid for that state.
 */
@Composable
fun ModelCard(
    info: ModelInfo,
    state: ModelDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CppIde.dimens

    CppCard(modifier = modifier.fillMaxWidth()) {
        SectionText(text = info.displayName)
        Spacer(Modifier.height(dimens.spacingXs))
        CaptionText(text = info.description)
        Spacer(Modifier.height(dimens.spacingM))

        StatusLine(info = info, state = state)

        if (state is ModelDownloadState.Downloading) {
            Spacer(Modifier.height(dimens.spacingS))
            ProgressBar(progress = state.progress)
        }

        Spacer(Modifier.height(dimens.spacingL))
        Actions(
            state = state,
            onDownload = onDownload,
            onCancel = onCancel,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun StatusLine(info: ModelInfo, state: ModelDownloadState) {
    val colors = CppIde.colors
    val label = when (state) {
        ModelDownloadState.NotDownloaded -> "Not downloaded · ${formatBytes(info.sizeBytes)}"
        is ModelDownloadState.Downloading -> {
            val pct = (state.progress * 100).toInt()
            "Downloading · ${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)} ($pct%)"
        }
        ModelDownloadState.Downloaded -> "Downloaded · ready to use"
        is ModelDownloadState.Failed -> "Failed · ${state.message}"
    }
    val color = when (state) {
        is ModelDownloadState.Failed -> colors.diagnosticError
        ModelDownloadState.Downloaded -> colors.accent
        else -> colors.textSecondary
    }
    BodyText(text = label, color = color)
}

@Composable
private fun ProgressBar(progress: Float) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusS)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(colors.border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .background(colors.accent),
        )
    }
}

@Composable
private fun Actions(
    state: ModelDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val dimens = CppIde.dimens
    Row(
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            ModelDownloadState.NotDownloaded -> {
                CppButton(text = "Download", onClick = onDownload)
            }
            is ModelDownloadState.Downloading -> {
                CppButton(text = "Cancel", onClick = onCancel, style = CppButtonStyle.Secondary)
            }
            ModelDownloadState.Downloaded -> {
                CppButton(text = "Delete", onClick = onDelete, style = CppButtonStyle.Secondary)
            }
            is ModelDownloadState.Failed -> {
                CppButton(text = "Retry", onClick = onDownload)
                Spacer(Modifier.width(dimens.spacingS))
                CppButton(text = "Clear", onClick = onDelete, style = CppButtonStyle.Ghost)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val gb = 1024.0 * 1024 * 1024
    val mb = 1024.0 * 1024
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.0f MB", bytes / mb)
        else -> "${bytes / 1024} KB"
    }
}
