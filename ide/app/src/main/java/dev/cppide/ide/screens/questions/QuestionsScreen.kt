package dev.cppide.ide.screens.questions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cppide.core.chat.Conversation
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CaptionText
import dev.cppide.ide.components.CppCard
import dev.cppide.ide.components.CppIconButton
import dev.cppide.ide.components.CppTopBar
import dev.cppide.ide.components.SectionText
import dev.cppide.ide.theme.CppIde
import dev.cppide.ide.util.slugToTitle

@Composable
fun QuestionsScreen(
    conversations: List<Conversation>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
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
            title = "My Questions",
            leading = {
                CppIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            },
        )

        val navInsets = WindowInsets.navigationBars.asPaddingValues()
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = dimens.spacingL,
                end = dimens.spacingL,
                top = dimens.spacingL,
                bottom = dimens.spacingL + navInsets.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
        ) {
            if (conversations.isEmpty() && !isLoading) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = dimens.spacingXxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(dimens.spacingM),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QuestionAnswer,
                            contentDescription = null,
                            tint = colors.textDisabled,
                            modifier = Modifier.size(48.dp),
                        )
                        CaptionText("No questions yet")
                        CaptionText("Open an exercise and use the Chat tab to ask Shahid Khan")
                    }
                }
            }

            // Group conversations by category.
            val grouped = conversations.groupBy { it.categorySlug }
            grouped.forEach { (categorySlug, convs) ->
                item {
                    SectionText(categorySlug.slugToTitle())
                }
                items(items = convs, key = { it.id }) { conv ->
                    ConversationCard(
                        conversation = conv,
                        onClick = { onOpenConversation(conv) },
                    )
                }
                item { Spacer(Modifier.height(dimens.spacingS)) }
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    CppCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BodyText(
                    text = conversation.exerciseSlug.slugToTitle(),
                )
                CaptionText(
                    text = "${conversation.messageCount} messages · ${conversation.filePath.substringAfterLast("/")}",
                )
            }
            if (conversation.unreadCount > 0) {
                BadgedBox(
                    badge = {
                        Badge {
                            CaptionText(
                                "${conversation.unreadCount}",
                                color = CppIde.colors.textOnAccent,
                            )
                        }
                    },
                ) {}
            }
        }
    }
}
