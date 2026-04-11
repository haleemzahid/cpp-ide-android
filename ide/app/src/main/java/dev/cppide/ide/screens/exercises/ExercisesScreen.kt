package dev.cppide.ide.screens.exercises

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde

/**
 * Stateless exercises catalog. Shows every category header followed by
 * its exercises; each exercise row has a download button that becomes
 * a spinner while the category is downloading, a check when done, and
 * a red error circle on failure.
 */
@Composable
fun ExercisesScreen(
    state: ExercisesState,
    onBack: () -> Unit,
    onDownload: (categorySlug: String, exerciseSlug: String) -> Unit,
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
            title = "Exercises",
            subtitle = "Download and start coding",
            leading = {
                CppIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            },
        )

        val bottomInset = WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()

        when {
            state.loading && state.rows.isEmpty() -> LoadingPane()
            state.errorMessage != null && state.rows.isEmpty() -> ErrorPane(state.errorMessage)
            state.rows.isEmpty() -> EmptyPane()
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = dimens.spacingL,
                        end = dimens.spacingL,
                        top = dimens.spacingL,
                        bottom = dimens.spacingL + bottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacingS),
                ) {
                    items(
                        items = state.rows,
                        key = { row ->
                            when (row) {
                                is ExerciseRow.Header -> "h:${row.category.slug}"
                                is ExerciseRow.Item ->
                                    "i:${row.categorySlug}/${row.exerciseSlug}"
                            }
                        },
                    ) { row ->
                        when (row) {
                            is ExerciseRow.Header -> CategoryHeader(row)
                            is ExerciseRow.Item -> {
                                val status = state.statusByKey[
                                    ExercisesState.key(row.categorySlug, row.exerciseSlug),
                                ] ?: DownloadStatus.Idle
                                ExerciseItemRow(
                                    row = row,
                                    status = status,
                                    onDownload = {
                                        onDownload(row.categorySlug, row.exerciseSlug)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(row: ExerciseRow.Header) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = dimens.spacingM,
                bottom = dimens.spacingXs,
            ),
    ) {
        SectionText(text = row.category.title)
        row.category.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Spacer(Modifier.height(dimens.spacingXxs))
            CaptionText(text = desc)
        }
        Spacer(Modifier.height(dimens.spacingXxs))
        CaptionText(
            text = "${row.category.exerciseCount} exercise" +
                if (row.category.exerciseCount == 1) "" else "s",
        )
    }
}

@Composable
private fun ExerciseItemRow(
    row: ExerciseRow.Item,
    status: DownloadStatus,
    onDownload: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusM)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.borderHairline, colors.border, shape)
            .padding(horizontal = dimens.spacingL, vertical = dimens.spacingM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
    ) {
        // Numbered circle so the student can glance at order.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                text = (row.orderIndex + 1).toString(),
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            BodyText(text = row.exerciseTitle, maxLines = 1)
            CaptionText(text = row.categoryTitle)
        }

        when (status) {
            DownloadStatus.Idle -> CppIconButton(
                icon = Icons.Outlined.Download,
                contentDescription = "Download",
                onClick = onDownload,
            )
            DownloadStatus.Downloading -> Box(
                modifier = Modifier.size(dimens.iconButtonSize),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                )
            }
            DownloadStatus.Done -> Box(
                modifier = Modifier.size(dimens.iconButtonSize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = colors.accent,
                    modifier = Modifier.size(dimens.iconSize),
                )
            }
            DownloadStatus.Failed -> CppIconButton(
                icon = Icons.Outlined.ErrorOutline,
                contentDescription = "Retry",
                onClick = onDownload,
                tint = CppIde.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun LoadingPane() {
    val dimens = CppIde.dimens
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = CppIde.colors.accent,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(dimens.spacingM))
        CaptionText("Loading exercises…")
    }
}

@Composable
private fun ErrorPane(message: String) {
    val dimens = CppIde.dimens
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.spacingXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = CppIde.colors.textSecondary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(dimens.spacingM))
        BodyText(text = "Couldn't load exercises")
        Spacer(Modifier.height(dimens.spacingXs))
        CaptionText(text = message)
    }
}

@Composable
private fun EmptyPane() {
    val dimens = CppIde.dimens
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CaptionText("No exercises available yet")
    }
}
