package dev.cppide.ide.screens.questions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.cppide.core.Core
import dev.cppide.core.chat.Conversation

@Composable
fun QuestionsRoute(
    core: Core,
    onBack: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
) {
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        core.chatApi.listConversations()
            .onSuccess { conversations = it }
        isLoading = false
    }

    QuestionsScreen(
        conversations = conversations,
        isLoading = isLoading,
        onBack = onBack,
        onOpenConversation = onOpenConversation,
    )
}
