package dev.cppide.ide.screens.about

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Rocket
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde

/**
 * Stateless About screen. Static content — author bio and contact
 * shortcuts. Emits click intents up to the route so the host activity
 * can open external intents (mail, browser).
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenDawlify: () -> Unit,
    onOpenInstagram: () -> Unit,
    onEmail: () -> Unit,
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
            title = "About",
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = dimens.spacingL,
                        end = dimens.spacingL,
                        top = dimens.spacingXl,
                        bottom = dimens.spacingL + bottomInset,
                    ),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.spacingL),
        ) {
            Avatar(initials = "SK")

            Text(
                text = "Shahid Khan",
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            CaptionText(
                text = "Developer · Founder of dawlify.com",
            )

            Spacer(Modifier.height(dimens.spacingS))

            BioCard(
                text = "I create solutions that solve real problems.",
            )

            SectionText(
                text = "Contact",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = dimens.spacingM),
            )

            ContactRow(
                icon = Icons.Outlined.Language,
                label = "Website",
                value = "https://shahidkhan.dev",
                onClick = onOpenWebsite,
                valueAsLink = true,
            )
            ContactRow(
                icon = Icons.Outlined.Rocket,
                label = "Dawlify",
                value = "dawlify.com",
                onClick = onOpenDawlify,
            )
            ContactRow(
                icon = Icons.Outlined.PhotoCamera,
                label = "Instagram",
                value = "@shahid_khan_dev",
                onClick = onOpenInstagram,
                valueAsLink = true,
            )
            ContactRow(
                icon = Icons.Outlined.Email,
                label = "Email",
                value = "shahidkhan.dev88@gmail.com",
                onClick = onEmail,
            )
        }
    }
}

@Composable
private fun Avatar(initials: String) {
    val colors = CppIde.colors
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = colors.textOnAccent,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BioCard(text: String) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusL)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.borderHairline, colors.border, shape)
            .padding(dimens.spacingL),
    ) {
        BodyText(text = text, color = colors.textPrimary)
    }
}

@Composable
private fun ContactRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    valueAsLink: Boolean = false,
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
            .clickable(onClick = onClick)
            .padding(
                horizontal = dimens.spacingL,
                vertical = dimens.spacingM,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingL),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(dimens.iconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            CaptionText(text = label)
            BodyText(
                text = value,
                color = if (valueAsLink) colors.accent else colors.textPrimary,
                textDecoration = if (valueAsLink) TextDecoration.Underline else null,
            )
        }
    }
}
