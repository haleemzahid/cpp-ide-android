package dev.cppide.ide.screens.editor.components

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.cppide.core.lsp.LspCompletion
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
 * The editor manages its own content; we only feed it [initialContent] once
 * per [fileId]. Switching files (different [fileId]) recreates the editor
 * via Compose's [key]. Within a file, the editor is the source of truth —
 * we forward keystrokes UP via [onContentChange] but never push state DOWN.
 *
 * This avoids the echo loop where each keystroke would trigger a state
 * update → recomposition → setText → another ContentChangeEvent. With
 * sora-editor's TextMate highlighter, that loop allocates ~30 MB per
 * keystroke and OOMs after a few characters.
 */
@Composable
fun EditorPane(
    fileId: String,
    initialContent: String,
    onContentChange: (String) -> Unit,
    onRequestCompletion: suspend (liveContent: String, line: Int, column: Int) -> List<LspCompletion>,
    modifier: Modifier = Modifier,
    languageScope: String = "source.cpp",
) {
    val colors = CppIde.colors
    // Capture the latest callbacks so the AndroidView listeners (attached
    // once per editor instance) always call the freshest lambdas.
    val callback = rememberUpdatedState(onContentChange)
    val completionCallback = rememberUpdatedState(onRequestCompletion)

    key(fileId) {
        AndroidView(
            modifier = modifier
                .fillMaxSize()
                .background(colors.editorBackground),
            factory = { context ->
                CodeEditor(context).apply {
                    // Theme + language. LspCppLanguage wraps TextMateLanguage
                    // so we keep syntax highlighting, bracket/indent logic,
                    // etc., while replacing the identifier-based
                    // autocompleter with real clangd completions.
                    colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                    val textMate = TextMateLanguage.create(
                        languageScope, GrammarRegistry.getInstance(), true
                    )
                    setEditorLanguage(
                        LspCppLanguage(textMate) { liveContent, line, column ->
                            completionCallback.value(liveContent, line, column)
                        }
                    )

                    // Visuals — pull from CppIde tokens.
                    typefaceText = Typeface.MONOSPACE
                    typefaceLineNumber = Typeface.MONOSPACE
                    setTextSize(13f)
                    isLineNumberEnabled = true
                    isCursorAnimationEnabled = false
                    isHighlightCurrentLine = true
                    tabWidth = 4
                    isWordwrap = false

                    // Initial content. Set once; the editor owns it from here.
                    setText(initialContent)

                    // Forward edits to Compose state. We deliberately do NOT
                    // compare to the parameter `initialContent` here — that
                    // would compare against a stale captured value across
                    // recompositions and miss legitimate edits.
                    subscribeAlways(ContentChangeEvent::class.java) { _ ->
                        callback.value(text.toString())
                    }
                }
            },
            // No-op update — the editor manages its own content. Switching
            // files is handled by the surrounding `key(fileId)` block, which
            // disposes and recreates the editor with the new initialContent.
            update = { /* nothing */ },
            onRelease = { editor -> editor.release() },
        )
    }
}
