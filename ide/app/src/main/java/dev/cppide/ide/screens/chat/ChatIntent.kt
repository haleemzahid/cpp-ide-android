package dev.cppide.ide.screens.chat

/** User-driven events from the stateless [ChatScreen] to [ChatViewModel]. */
sealed interface ChatIntent {
    data class UpdateInput(val text: String) : ChatIntent
    data class SelectModel(val modelId: String) : ChatIntent
    data object Send : ChatIntent
    data object Stop : ChatIntent
    data object Reset : ChatIntent
}
