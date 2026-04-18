package dev.cppide.ide.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.cppide.ide.R
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppButton
import dev.cppide.ide.components.CppButtonStyle
import dev.cppide.ide.components.CppTextField
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde

@Composable
fun AuthScreen(
    isLogin: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onSubmit: (name: String?, email: String, password: String, rememberMe: Boolean) -> Unit,
    onToggleMode: () -> Unit,
    /** Dismiss the auth screen and carry on without logging in. Persists so
     *  the welcome screen can hide the nag. Only relevant for callers who
     *  show auth as a prompt — explicit login taps can ignore it. */
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val focusManager = LocalFocusManager.current

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }

    fun submit() {
        if (isLoading) return
        onSubmit(
            if (isLogin) null else name.trim(),
            email.trim(),
            password,
            rememberMe,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.spacingXxl, vertical = dimens.spacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (onSkip != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                BodyText(
                    text = "Skip",
                    color = colors.accent,
                    modifier = Modifier
                        .clickable(enabled = !isLoading, onClick = onSkip)
                        .padding(dimens.spacingS),
                )
            }
            Spacer(Modifier.height(dimens.spacingM))
        }

        // Logo
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "C++ IDE",
                modifier = Modifier.size(56.dp),
            )
        }

        Spacer(Modifier.height(dimens.spacingL))

        SectionText(
            text = "C++ IDE",
        )

        Spacer(Modifier.height(dimens.spacingXxl))

        SectionText(
            text = if (isLogin) "Welcome back" else "Create account",
        )

        Spacer(Modifier.height(dimens.spacingXl))

        if (!isLogin) {
            CppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Name",
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
            )
            Spacer(Modifier.height(dimens.spacingL))
        }

        CppTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = "Email",
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            ),
        )

        Spacer(Modifier.height(dimens.spacingL))

        CppTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = "Password",
            enabled = !isLoading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { submit() },
            ),
        )

        Spacer(Modifier.height(dimens.spacingM))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = rememberMe,
                onCheckedChange = { rememberMe = it },
                enabled = !isLoading,
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.accent,
                    uncheckedColor = colors.textSecondary,
                ),
            )
            BodyText("Remember me")
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(dimens.spacingM))
            CaptionText(
                text = errorMessage,
                color = colors.diagnosticError,
            )
        }

        Spacer(Modifier.height(dimens.spacingXl))

        CppButton(
            text = if (isLoading) "Please wait…" else if (isLogin) "Log in" else "Sign up",
            onClick = { submit() },
            enabled = !isLoading,
            style = CppButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(dimens.spacingXxl))

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BodyText(
                text = if (isLogin) "Don't have an account? " else "Already have an account? ",
                color = colors.textSecondary,
            )
            BodyText(
                text = if (isLogin) "Sign up" else "Log in",
                color = colors.accent,
                modifier = Modifier.clickable(onClick = onToggleMode),
            )
        }
    }
}
