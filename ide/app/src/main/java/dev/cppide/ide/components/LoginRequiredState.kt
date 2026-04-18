package dev.cppide.ide.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cppide.ide.theme.CppIde

/**
 * Full-panel placeholder shown when a screen (Questions, upload, etc.)
 * needs an authenticated session but the user hasn't logged in.
 * Centers a lock icon, an explanation, and a primary "Log in" button.
 */
@Composable
fun LoginRequiredState(
    message: String,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(dimens.spacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = colors.textDisabled,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(dimens.spacingL))
        BodyText(text = "Login required")
        Spacer(Modifier.height(dimens.spacingS))
        CaptionText(text = message)
        Spacer(Modifier.height(dimens.spacingXl))
        CppButton(
            text = "Log in",
            onClick = onLogin,
            style = CppButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
