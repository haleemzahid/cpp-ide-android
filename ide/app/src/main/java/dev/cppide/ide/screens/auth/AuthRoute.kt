package dev.cppide.ide.screens.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.cppide.core.Core
import kotlinx.coroutines.launch

@Composable
fun AuthRoute(
    core: Core,
    onAuthenticated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isLogin by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AuthScreen(
        isLogin = isLogin,
        isLoading = isLoading,
        errorMessage = errorMessage,
        onSubmit = { name, email, password, rememberMe ->
            isLoading = true
            errorMessage = null
            scope.launch {
                val result = if (isLogin) {
                    core.studentAuth.login(email, password, rememberMe)
                } else {
                    core.studentAuth.signup(name ?: "", email, password, rememberMe)
                }
                isLoading = false
                result
                    .onSuccess { onAuthenticated() }
                    .onFailure { e ->
                        errorMessage = e.message ?: "Something went wrong"
                    }
            }
        },
        onToggleMode = {
            isLogin = !isLogin
            errorMessage = null
        },
    )
}
