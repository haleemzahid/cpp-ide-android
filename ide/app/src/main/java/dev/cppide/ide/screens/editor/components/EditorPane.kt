package dev.cppide.ide.screens.editor.components

import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.cppide.ide.theme.CppIde
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Code editor view backed by sora-editor with TextMate syntax highlighting.
 *
 * The composable wraps sora's [CodeEditor] in an [AndroidView]. The
 * Compose state ([content]) is the source of truth — when it changes
 * externally (e.g. opening a different file), we push it into the editor.
 * When the user types, the editor fires [ContentChangeEvent], we forward
 * the new text via [onContentChange] and skip the next external update
 * to avoid re-pushing the same value back into the editor.
 *
 * sora-editor handles its own scrolling, magnifier, selection handles,
 * gutter, line numbers, bracket matching and auto-indent — we just
 * configure visuals via [CppIde] tokens.
 */
@Composable
fun EditorPane(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    languageScope: String = "source.cpp",
) {
    val ctx = LocalContext.current
    val colors = CppIde.colors

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(colors.editorBackground),
        factory = { context ->
            CodeEditor(context).apply {
                // Theme + language
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                setEditorLanguage(
                    TextMateLanguage.create(languageScope, GrammarRegistry.getInstance(), true)
                )

                // Visuals — pull from CppIde tokens (any later restyle just
                // bumps these values; the editor picks them up here).
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                setTextSize(13f)  // matches CppIde.typography.code default
                isLineNumberEnabled = true
                isCursorAnimationEnabled = false
                isHighlightCurrentLine = true
                isHighlightCurrentBlock = true
                tabWidth = 4
                isWordwrap = false

                // Initial content
                setText(content)

                // Forward edits up to Compose state.
                subscribeAlways(ContentChangeEvent::class.java) { _ ->
                    val current = text.toString()
                    if (current != content) onContentChange(current)
                }
            }
        },
        update = { editor ->
            // Only push state down if it actually differs from what the
            // editor already shows — otherwise we'd loop with the listener
            // above and reset the cursor on every keystroke.
            if (editor.text.toString() != content) {
                editor.setText(content)
            }
        },
        onRelease = { editor ->
            editor.release()
        },
    )
}
