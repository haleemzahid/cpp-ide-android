package dev.cppide.ide.screens.exercises

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.core.exercises.ExerciseCategory
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppButton
import dev.cppide.ide.components.CppButtonStyle
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.theme.CppIde

/**
 * Stateless exercises catalog. One card per category — the student
 * taps "Download all" and the whole exercise set arrives as a single
 * local project. Scales to 100+ exercises per category without making
 * the student tap 100 times.
 */
@Composable
fun ExercisesScreen(
    state: ExercisesState,
    onBack: () -> Unit,
    onDownload: (categorySlug: String) -> Unit,
    onOpen: (categorySlug: String) -> Unit,
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
            subtitle = "Pick a category",
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
            state.loading && state.categories.isEmpty() -> LoadingPane()
            state.errorMessage != null && state.categories.isEmpty() ->
                ErrorPane(state.errorMessage)
            state.categories.isEmpty() -> EmptyPane()
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = dimens.spacingL,
                        end = dimens.spacingL,
                        top = dimens.spacingL,
                        bottom = dimens.spacingL + bottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
                ) {
                    items(items = state.categories, key = { it.slug }) { category ->
                        val status = state.statusBySlug[category.slug] ?: DownloadStatus.Idle
                        CategoryCard(
                            category = category,
                            status = status,
                            onDownload = { onDownload(category.slug) },
                            onOpen = { onOpen(category.slug) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: ExerciseCategory,
    status: DownloadStatus,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusL)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.borderHairline, colors.border, shape)
            .padding(dimens.spacingL),
        verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
    ) {
        // Header row: icon circle + title + exercise count badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.School,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(dimens.iconSize),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    text = category.title,
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                CaptionText(
                    text = "${category.exerciseCount} exercise" +
                        if (category.exerciseCount == 1) "" else "s",
                )
            }
        }

        // Optional description
        category.description?.takeIf { it.isNotBlank() }?.let { desc ->
            BodyText(text = desc, color = colors.textSecondary)
        }

        // Action row — single button that swaps in spinner/check/retry.
        DownloadButton(
            status = status,
            onDownload = onDownload,
            onOpen = onOpen,
        )
    }
}

@Composable
private fun DownloadButton(
    status: DownloadStatus,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    when (status) {
        DownloadStatus.Idle -> CppButton(
            text = "Download all",
            onClick = onDownload,
            style = CppButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        DownloadStatus.Downloading -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.radiusS))
                .background(colors.accent.copy(alpha = 0.15f))
                .padding(vertical = dimens.spacingM),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.accent,
            )
            Spacer(Modifier.size(dimens.spacingM))
            CaptionText(text = "Downloading…", color = colors.accent)
        }
        DownloadStatus.Done -> CppButton(
            text = "Open project",
            onClick = onOpen,
            style = CppButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        DownloadStatus.Failed -> CppButton(
            text = "Retry download",
            onClick = onDownload,
            style = CppButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
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
        CaptionText("Loading categories…")
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
        BodyText(text = "Couldn't load categories")
        Spacer(Modifier.height(dimens.spacingXs))
        CaptionText(text = message)
    }
}

@Composable
private fun EmptyPane() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CaptionText("No categories available yet")
    }
}
