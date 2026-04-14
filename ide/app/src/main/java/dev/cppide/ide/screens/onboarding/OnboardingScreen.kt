package dev.cppide.ide.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppButton
import dev.cppide.ide.components.CppButtonStyle
import dev.cppide.ide.theme.CppIde
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val features: List<Feature> = emptyList(),
    val accentGradient: List<Color>,
)

private data class Feature(
    val icon: ImageVector,
    val text: String,
)

private val pages = listOf(
    OnboardingPage(
        icon = Icons.Outlined.Code,
        title = "Learn C++\nby Doing",
        subtitle = "A real IDE on your phone — write, compile, and run C++ anywhere.",
        accentGradient = listOf(Color(0xFF007ACC), Color(0xFF0098FF)),
    ),
    OnboardingPage(
        icon = Icons.Outlined.RocketLaunch,
        title = "Everything\nYou Need",
        subtitle = "No setup, no cloud — everything runs on your device.",
        features = listOf(
            Feature(Icons.Outlined.Code, "Full code editor with syntax highlighting"),
            Feature(Icons.Outlined.Terminal, "Compile & run with one tap"),
            Feature(Icons.Outlined.BugReport, "Built-in debugger with breakpoints"),
            Feature(Icons.Outlined.School, "Guided exercises to learn step by step"),
        ),
        accentGradient = listOf(Color(0xFF4EC9B0), Color(0xFF38B89A)),
    ),
    OnboardingPage(
        icon = Icons.Outlined.QuestionAnswer,
        title = "Stuck?\nJust Ask.",
        subtitle = "Chat with your teacher directly from the editor. Your code is shared automatically so they see exactly where you are.",
        accentGradient = listOf(Color(0xFFCCA700), Color(0xFFE6BE00)),
    ),
    OnboardingPage(
        icon = Icons.Outlined.Favorite,
        title = "Built for You",
        subtitle = "Designed & built by Shahid Khan\nso you can focus on learning, not fighting tools.",
        accentGradient = listOf(Color(0xFFF14C4C), Color(0xFFFF6B6B)),
    ),
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val isLastPage = pagerState.currentPage == pages.lastIndex
    val navInsets = WindowInsets.navigationBars.asPaddingValues()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = navInsets.calculateBottomPadding()),
    ) {
        // Pages
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { pageIndex ->
            PageContent(
                page = pages[pageIndex],
                isActive = pagerState.currentPage == pageIndex,
            )
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.spacingXxl, vertical = dimens.spacingXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Page dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.forEachIndexed { index, page ->
                    val isActive = pagerState.currentPage == index
                    val width by animateFloatAsState(
                        targetValue = if (isActive) 24f else 8f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "dot",
                    )
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(width.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive)
                                    Brush.horizontalGradient(page.accentGradient)
                                else
                                    Brush.horizontalGradient(
                                        listOf(colors.border, colors.border)
                                    )
                            ),
                    )
                }
            }

            Spacer(Modifier.height(dimens.spacingXxl))

            // Button
            if (isLastPage) {
                CppButton(
                    text = "Get Started",
                    onClick = onFinish,
                    style = CppButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CppButton(
                    text = "Next",
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    style = CppButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(dimens.spacingM))

            if (!isLastPage) {
                CppButton(
                    text = "Skip",
                    onClick = onFinish,
                    style = CppButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PageContent(
    page: OnboardingPage,
    isActive: Boolean,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val typography = CppIde.typography

    // Animate icon scale when page becomes active
    val iconScale = remember { Animatable(0.5f) }
    LaunchedEffect(isActive) {
        if (isActive) {
            iconScale.snapTo(0.5f)
            iconScale.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = dimens.spacingXxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Gradient icon circle
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(iconScale.value)
                .clip(CircleShape)
                .background(Brush.linearGradient(page.accentGradient)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(Modifier.height(dimens.spacingXxxl))

        // Title
        Text(
            text = page.title,
            style = typography.titleLarge.copy(
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(dimens.spacingL))

        // Subtitle
        Text(
            text = page.subtitle,
            style = typography.bodyLarge,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = dimens.spacingL),
        )

        // Feature list (only on page 2)
        if (page.features.isNotEmpty()) {
            Spacer(Modifier.height(dimens.spacingXxl))
            Column(
                verticalArrangement = Arrangement.spacedBy(dimens.spacingL),
                modifier = Modifier.fillMaxWidth(),
            ) {
                page.features.forEachIndexed { index, feature ->
                    FeatureRow(
                        feature = feature,
                        gradientColors = page.accentGradient,
                        isActive = isActive,
                        delayMs = index * 100,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    feature: Feature,
    gradientColors: List<Color>,
    isActive: Boolean,
    delayMs: Int,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    val visible = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive) {
            visible.value = false
            delay(delayMs.toLong() + 200)
            visible.value = true
        } else {
            visible.value = false
        }
    }

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(tween(300)) + slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = tween(300, easing = FastOutSlowInEasing),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.radiusM))
                .background(colors.surface)
                .padding(horizontal = dimens.spacingL, vertical = dimens.spacingM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingL),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(dimens.radiusS))
                    .background(Brush.linearGradient(gradientColors)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            BodyText(text = feature.text)
        }
    }
}
