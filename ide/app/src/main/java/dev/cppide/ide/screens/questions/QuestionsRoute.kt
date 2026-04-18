package dev.cppide.ide.screens.questions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.cppide.core.Core
import dev.cppide.core.chat.Conversation
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.components.LoginRequiredState
import dev.cppide.ide.theme.CppIde

@Composable
fun QuestionsRoute(
    core: Core,
    onBack: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onLogin: () -> Unit,
) {
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val session by core.studentAuth.session.collectAsState()
    val isLoggedIn = session != null

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            isLoading = true
            core.chatApi.listConversations()
                .onSuccess { conversations = it }
            isLoading = false
        } else {
            conversations = emptyList()
            isLoading = false
        }
    }

    if (!isLoggedIn) {
        Column(modifier = Modifier.fillMaxSize().background(CppIde.colors.background)) {
            CppTopBar(
                title = "My Questions",
                leading = {
                    CppIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                    )
                },
            )
            LoginRequiredState(
                message = "Log in to see questions you've asked and continue conversations.",
                onLogin = onLogin,
            )
        }
        return
    }

    QuestionsScreen(
        conversations = conversations,
        isLoading = isLoading,
        onBack = onBack,
        onOpenConversation = onOpenConversation,
    )
}
