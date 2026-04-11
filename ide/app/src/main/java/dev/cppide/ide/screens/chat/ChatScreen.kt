package dev.cppide.ide.screens.chat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.cppide.core.ai.AiEngineState
import dev.cppide.core.ai.ModelInfo
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppButton
import dev.cppide.ide.components.CppButtonStyle
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTextField
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.theme.CppIde

/**
 * Stateless chat screen. Renders [ChatState] and emits [ChatIntent]s.
 * Wired to the engine via [ChatRoute].
 */
@Composable
fun ChatScreen(
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
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
            title = "Assistant",
            subtitle = state.subtitle(),
            leading = {
                CppIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            },
            trailing = {
                CppIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "New conversation",
                    onClick = { onIntent(ChatIntent.Reset) },
                )
            },
        )

        if (state.downloadedModels.isEmpty()) {
            NoModelsState(onOpenSettings = onOpenSettings)
            return@Column
        }

        // Horizontal scrollable row of model "chips" — tiny, compact
        // picker that doesn't steal much vertical space.
        ModelPickerRow(
            models = state.downloadedModels,
            selectedId = state.selectedModelId,
            onSelect = { onIntent(ChatIntent.SelectModel(it)) },
        )

        MessageList(
            state = state,
            modifier = Modifier.weight(1f),
        )

        InputRow(
            state = state,
            onIntent = onIntent,
        )
    }
}

private fun ChatState.subtitle(): String? {
    val id = selectedModelId ?: return if (downloadedModels.isEmpty()) "No models downloaded" else null
    val name = downloadedModels.firstOrNull { it.id == id }?.displayName ?: id
    return when (val e = engine) {
        AiEngineState.Idle -> name
        is AiEngineState.Loading -> "$name · loading…"
        is AiEngineState.Ready -> "$name · ready"
        is AiEngineState.Generating -> "$name · generating…"
        is AiEngineState.Failed -> "$name · ${e.message}"
    }
}

@Composable
private fun NoModelsState(onOpenSettings: () -> Unit) {
    val dimens = CppIde.dimens
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.spacingXxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BodyText(
            text = "No AI models downloaded yet.",
        )
        Spacer(Modifier.height(dimens.spacingM))
        CaptionText(
            text = "Download a model from Settings to start chatting. Qwen 3 0.6B is the smallest and fastest to try.",
        )
        Spacer(Modifier.height(dimens.spacingL))
        CppButton(
            text = "Open Settings",
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun ModelPickerRow(
    models: List<ModelInfo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = dimens.spacingL, vertical = dimens.spacingS),
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        models.forEach { model ->
            ModelChip(
                label = model.displayName,
                selected = model.id == selectedId,
                onClick = { onSelect(model.id) },
            )
        }
    }
}

@Composable
private fun ModelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val shape = RoundedCornerShape(dimens.radiusS)
    val bg = if (selected) colors.accent else colors.background
    val fg = if (selected) colors.textOnAccent else colors.textPrimary
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(dimens.borderHairline, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.spacingM, vertical = dimens.spacingS),
    ) {
        BodyText(text = label, color = fg)
    }
}

@Composable
private fun MessageList(
    state: ChatState,
    modifier: Modifier = Modifier,
) {
    val dimens = CppIde.dimens
    val listState = rememberLazyListState()

    // Autoscroll to the bottom as new tokens arrive.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        val lastIndex = state.messages.lastIndex
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimens.spacingL),
        verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
    ) {
        if (state.messages.isEmpty()) {
            item {
                CaptionText(
                    text = "Type a message below to start.",
                    modifier = Modifier.padding(vertical = dimens.spacingXl),
                )
            }
        } else {
            itemsIndexed(state.messages, key = { idx, _ -> idx }) { _, msg ->
                MessageBubble(msg)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val isUser = msg.role == ChatMessage.Role.User
    val bg = if (isUser) colors.accent else colors.surface
    val fg = if (isUser) colors.textOnAccent else colors.textPrimary
    val shape = RoundedCornerShape(dimens.radiusM)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(bg)
                .border(dimens.borderHairline, colors.border, shape)
                .padding(horizontal = dimens.spacingL, vertical = dimens.spacingM),
        ) {
            val suffix = if (msg.streaming && msg.text.isEmpty()) "…" else ""
            BodyText(
                text = msg.text + suffix,
                color = fg,
            )
        }
    }
}

@Composable
private fun InputRow(
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val isBusy = state.engine is AiEngineState.Generating || state.engine is AiEngineState.Loading
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .imePadding()
            .padding(
                start = dimens.spacingL,
                end = dimens.spacingL,
                top = dimens.spacingM,
                bottom = dimens.spacingM + bottomInset,
            ),
    ) {
        CppTextField(
            value = state.inputText,
            onValueChange = { onIntent(ChatIntent.UpdateInput(it)) },
            placeholder = "Ask anything…",
            enabled = !isBusy,
        )
        Spacer(Modifier.height(dimens.spacingS))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isBusy) {
                CppButton(
                    text = if (state.engine is AiEngineState.Loading) "Loading…" else "Stop",
                    onClick = { onIntent(ChatIntent.Stop) },
                    style = CppButtonStyle.Secondary,
                    enabled = state.engine !is AiEngineState.Loading,
                )
            } else {
                CppButton(
                    text = "Send",
                    onClick = { onIntent(ChatIntent.Send) },
                    enabled = state.inputText.isNotBlank() && state.selectedModelId != null,
                )
            }
        }
    }
}
