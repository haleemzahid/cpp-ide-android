package dev.cppide.ide.screens.editor.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.cppide.core.chat.ChatMessage
import dev.cppide.core.chat.SenderRole
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CodeText
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTextField
import dev.cppide.ide.theme.CppIde
import dev.cppide.ide.util.formatRelativeIso

@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    inputText: String,
    isSending: Boolean,
    isLoading: Boolean,
    isCppFile: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    if (!isCppFile) {
        EmptyChatState(
            icon = Icons.Outlined.School,
            message = "Open a .cpp file to chat with Shahid Khan",
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = dimens.spacingL,
                vertical = dimens.spacingM,
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = dimens.spacingXl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = colors.accent,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else if (messages.isEmpty()) {
                item {
                    EmptyChatHint()
                }
            }
            itemsIndexed(messages, key = { _, m -> m.id }) { index, msg ->
                val showSenderLabel = index == 0 ||
                    messages[index - 1].senderRole != msg.senderRole
                ChatBubble(
                    msg = msg,
                    showSenderLabel = showSenderLabel,
                )
            }
        }

        // Auto-attach indicator
        if (inputText.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = dimens.spacingL, vertical = dimens.spacingXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spacingXs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = null,
                    tint = colors.textDisabled,
                    modifier = Modifier.size(dimens.iconSizeSmall),
                )
                CaptionText(
                    text = "Your code & exercise prompt will be attached",
                    color = colors.textDisabled,
                )
            }
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceElevated)
                .padding(horizontal = dimens.spacingL, vertical = dimens.spacingM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                CppTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = if (messages.isEmpty()) "Ask Shahid Khan for help…" else "Reply…",
                    enabled = !isSending,
                )
            }
            // Send button — circular accent when active, muted when disabled
            val canSend = inputText.isNotBlank() && !isSending
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (canSend) colors.accent else colors.border)
                    .then(if (canSend) Modifier.clickable(onClick = onSend) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = colors.textOnAccent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = colors.textOnAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ---- Empty states ----

@Composable
private fun EmptyChatState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(dimens.spacingXxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textDisabled,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(dimens.spacingM))
        CaptionText(text = message)
    }
}

@Composable
private fun EmptyChatHint() {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.spacingXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacingS),
    ) {
        Icon(
            imageVector = Icons.Outlined.School,
            contentDescription = null,
            tint = colors.textDisabled,
            modifier = Modifier.size(28.dp),
        )
        CaptionText(text = "Ask Shahid Khan anything about this exercise")
        CaptionText(
            text = "Your code is automatically shared with each message",
            color = colors.textDisabled,
        )
    }
}

// ---- Message bubbles ----

@Composable
private fun ChatBubble(
    msg: ChatMessage,
    showSenderLabel: Boolean,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val isStudent = msg.senderRole == SenderRole.Student
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isStudent) Alignment.End else Alignment.Start,
    ) {
        // Sender label — only on first message in a consecutive run
        if (showSenderLabel) {
            CaptionText(
                text = if (isStudent) "You" else "Shahid Khan",
                color = colors.textDisabled,
                modifier = Modifier.padding(
                    bottom = dimens.spacingXs,
                    start = if (!isStudent) dimens.spacingXs else 0.dp,
                    end = if (isStudent) dimens.spacingXs else 0.dp,
                ),
            )
        }

        // Bubble
        val bubbleBg = if (isStudent) colors.accent else colors.surfaceElevated
        val bubbleFg = if (isStudent) colors.textOnAccent else colors.textPrimary
        val bubbleShape = RoundedCornerShape(
            topStart = dimens.radiusL,
            topEnd = dimens.radiusL,
            bottomStart = if (isStudent) dimens.radiusL else dimens.radiusS,
            bottomEnd = if (isStudent) dimens.radiusS else dimens.radiusL,
        )

        Column(
            modifier = Modifier
                .widthIn(max = screenWidth * 0.78f)
                .clip(bubbleShape)
                .background(bubbleBg)
                .padding(horizontal = dimens.spacingL, vertical = dimens.spacingM),
        ) {
            BodyText(text = msg.body, color = bubbleFg)

            // Code snapshot indicator — expandable
            val code = msg.codeSnapshot
            if (code != null) {
                Spacer(Modifier.height(dimens.spacingS))
                CodeAttachmentChip(
                    code = code,
                    isStudent = isStudent,
                )
            }

            // Timestamp
            Spacer(Modifier.height(dimens.spacingXs))
            CaptionText(
                text = formatRelativeIso(msg.createdAt),
                color = if (isStudent) bubbleFg.copy(alpha = 0.6f) else colors.textDisabled,
            )
        }
    }
}

@Composable
private fun CodeAttachmentChip(
    code: String,
    isStudent: Boolean,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    var expanded by remember { mutableStateOf(false) }

    val chipBg = if (isStudent)
        colors.textOnAccent.copy(alpha = 0.12f)
    else
        colors.background

    Column {
        // Chip
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(dimens.radiusS))
                .background(chipBg)
                .clickable { expanded = !expanded }
                .padding(horizontal = dimens.spacingM, vertical = dimens.spacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingXs),
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = if (isStudent) colors.textOnAccent.copy(alpha = 0.7f) else colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            CaptionText(
                text = if (expanded) "Hide code" else "View attached code",
                color = if (isStudent) colors.textOnAccent.copy(alpha = 0.7f) else colors.textSecondary,
            )
        }

        // Expandable code preview
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInVertically(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = dimens.spacingS)
                    .clip(RoundedCornerShape(dimens.radiusS))
                    .background(colors.editorBackground)
                    .border(dimens.borderHairline, colors.border, RoundedCornerShape(dimens.radiusS))
                    .padding(dimens.spacingM),
            ) {
                CodeText(
                    text = code.take(500) + if (code.length > 500) "\n…" else "",
                    color = colors.textPrimary,
                )
            }
        }
    }
}

