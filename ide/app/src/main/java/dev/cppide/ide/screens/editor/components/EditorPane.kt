package dev.cppide.ide.screens.editor.components

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.cppide.core.lsp.LspCompletion
import dev.cppide.ide.theme.CppIde
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.LongPressEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.launch

/**
 * Imperative actions the surrounding screen can fire at the sora editor.
 * Exposed via [EditorPane]'s `onControllerReady` callback so the top bar
 * can wire undo/redo buttons without the screen having to know what
 * concrete editor library is in use.
 */
interface EditorController {
    fun undo()
    fun redo()
}

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
 *
 * Long-press a symbol to trigger a clangd hover: the result is shown as
 * a floating card anchored at the top of the editor. A top-anchored card
 * is easier to read on a phone than a popup at the touch point (which
 * your finger would otherwise cover).
 */
@Composable
fun EditorPane(
    fileId: String,
    initialContent: String,
    onContentChange: (String) -> Unit,
    onRequestCompletion: suspend (liveContent: String, line: Int, column: Int) -> List<LspCompletion>,
    onRequestHover: suspend (line: Int, column: Int) -> String?,
    onControllerReady: (EditorController) -> Unit,
    modifier: Modifier = Modifier,
    languageScope: String = "source.cpp",
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    // Capture the latest callbacks so the AndroidView listeners (attached
    // once per editor instance) always call the freshest lambdas.
    val callback = rememberUpdatedState(onContentChange)
    val completionCallback = rememberUpdatedState(onRequestCompletion)
    val hoverCallback = rememberUpdatedState(onRequestHover)
    val scope = rememberCoroutineScope()

    // Currently-shown hover text; null when no tooltip is visible.
    var hoverText by remember(fileId) { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.editorBackground),
    ) {
        key(fileId) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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

                        // Forward edits to Compose state.
                        subscribeAlways(ContentChangeEvent::class.java) { _ ->
                            callback.value(text.toString())
                        }

                        // Long-press → clangd hover. The event fires on the
                        // editor thread; we hop onto the Compose scope so the
                        // suspend call + state update run where they should.
                        subscribeAlways(LongPressEvent::class.java) { evt ->
                            val line = evt.line
                            val column = evt.column
                            scope.launch {
                                val result = hoverCallback.value(line, column)
                                hoverText = result?.takeIf { it.isNotBlank() }
                            }
                        }

                        // Publish the controller AFTER the editor is fully
                        // configured. The top bar uses this to drive undo/
                        // redo without the screen knowing sora's API.
                        onControllerReady(object : EditorController {
                            override fun undo() { this@apply.undo() }
                            override fun redo() { this@apply.redo() }
                        })
                    }
                },
                // No-op update — the editor manages its own content. Switching
                // files is handled by the surrounding `key(fileId)` block, which
                // disposes and recreates the editor with the new initialContent.
                update = { /* nothing */ },
                onRelease = { editor -> editor.release() },
            )
        }

        // ---- hover tooltip overlay ----
        hoverText?.let { text ->
            HoverCard(
                text = text,
                onDismiss = { hoverText = null },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(dimens.spacingS),
            )
        }
    }
}

/**
 * Floating tooltip for clangd hover results. Top-anchored, dark surface,
 * monospace text, scrollable for long signatures, with an explicit close
 * button — on mobile an X is more discoverable than "tap outside".
 *
 * Clangd returns markdown in many cases; we render as plain text for v1
 * since the signature and type info is already readable without bold/
 * italic formatting.
 */
@Composable
private fun HoverCard(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    Surface(
        modifier = modifier.widthIn(max = 360.dp),
        color = colors.surfaceElevated,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(dimens.radiusM),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(
                    start = dimens.spacingM,
                    end = dimens.spacingS,
                    top = dimens.spacingS,
                    bottom = dimens.spacingM,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close hover",
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(dimens.spacingXs),
                )
            }
            Box(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = text,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
