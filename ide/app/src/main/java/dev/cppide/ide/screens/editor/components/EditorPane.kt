package dev.cppide.ide.screens.editor.components

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.cppide.core.lsp.LspCompletion
import dev.cppide.core.lsp.LspDiagnostic
import dev.cppide.ide.editor.TextMateBootstrap
import dev.cppide.ide.theme.CppIde
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorMotionEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

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
 */
@Composable
fun EditorPane(
    fileId: String,
    initialContent: String,
    onContentChange: (String) -> Unit,
    onRequestCompletion: suspend (liveContent: String, line: Int, column: Int) -> List<LspCompletion>,
    onToggleBreakpoint: (line: Int) -> Unit,
    onControllerReady: (EditorController) -> Unit,
    /** 1-indexed source lines with breakpoints (+ verified flag). The
     *  editor draws a red dot in the gutter for each entry. */
    breakpointLines: Map<Int, Boolean> = emptyMap(),
    /** 1-indexed source line currently being executed, or null when not
     *  stopped inside this file. The editor highlights the line and
     *  scrolls to it. */
    currentLine: Int? = null,
    /** clangd diagnostics for the currently open file. Rendered as squiggle
     *  underlines under the offending text, with Sora's built-in diagnostic
     *  tooltip window picking them up on cursor hover. */
    lspDiagnostics: List<LspDiagnostic> = emptyList(),
    modifier: Modifier = Modifier,
    languageScope: String = "source.cpp",
) {
    val colors = CppIde.colors
    val darkTheme = isSystemInDarkTheme()
    // Capture the latest callbacks so the AndroidView listeners (attached
    // once per editor instance) always call the freshest lambdas.
    val callback = rememberUpdatedState(onContentChange)
    val completionCallback = rememberUpdatedState(onRequestCompletion)
    val breakpointCallback = rememberUpdatedState(onToggleBreakpoint)

    // Live reference to the editor. DebugCodeEditor paints the current
    // execution line and breakpoint gutter markers inside its own
    // onDraw — no Compose overlay, no scroll-event tracking, no pixel
    // math that drifts during programmatic scrolls.
    var editorRef by remember(fileId) { mutableStateOf<DebugCodeEditor?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.editorBackground),
    ) {
        key(fileId, darkTheme) {
            AndroidView<DebugCodeEditor>(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    DebugCodeEditor(context).apply {
                        // Theme + language. LspCppLanguage wraps TextMateLanguage
                        // so we keep syntax highlighting, bracket/indent logic,
                        // etc., while replacing the identifier-based
                        // autocompleter with real clangd completions.
                        ThemeRegistry.getInstance().setTheme(
                            if (darkTheme) TextMateBootstrap.DARK_THEME_NAME
                            else TextMateBootstrap.LIGHT_THEME_NAME,
                        )
                        colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance()).apply {
                            // Sora's TextMate bridge leaves cursor + matched-bracket
                            // colors defaulted, so in Dark+ the cursor lands
                            // black-on-dark (invisible over `}` etc.). Pin both
                            // to the Compose token so they track the theme.
                            val cursorArgb = colors.editorCursor.toArgb()
                            setColor(EditorColorScheme.SELECTION_INSERT, cursorArgb)
                            setColor(EditorColorScheme.SELECTION_HANDLE, cursorArgb)
                        }
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
                        // Wider gutter so the breakpoint tap target is
                        // comfortable on a phone. Default is ~3-4 dp on
                        // each side which makes setting a breakpoint
                        // feel like trying to tap a hair. We bump both
                        // the left padding (before the numbers) and the
                        // divider gap (after the numbers) so the total
                        // gutter is closer to ~44 dp which matches
                        // Material's recommended touch target.
                        val dpUnitPx = dpUnit
                        setLineNumberMarginLeft(dpUnitPx * 14f)
                        setDividerMargin(dpUnitPx * 8f, dpUnitPx * 10f)

                        // Initial content. Set once; the editor owns it from here.
                        setText(initialContent)

                        // Forward edits to Compose state.
                        subscribeAlways(ContentChangeEvent::class.java) { _ ->
                            callback.value(text.toString())
                        }

                        // Tap on the line-number gutter → toggle breakpoint.
                        // sora reports the click's region via the motion
                        // event; REGION_LINE_NUMBER means "gutter".
                        subscribeAlways(ClickEvent::class.java) { evt ->
                            if (evt.motionRegion == EditorMotionEvent.REGION_LINE_NUMBER) {
                                // evt.line is 0-indexed, our breakpoint
                                // API uses 1-indexed lines to match what
                                // the user sees in the gutter.
                                breakpointCallback.value(evt.line + 1)
                            }
                        }

                        // Publish the controller AFTER the editor is fully
                        // configured. The top bar uses this to drive undo/
                        // redo without the screen knowing sora's API.
                        onControllerReady(object : EditorController {
                            override fun undo() { this@apply.undo() }
                            override fun redo() { this@apply.redo() }
                        })
                        editorRef = this@apply
                    }
                },
                // Apply Compose state changes to the editor view. Both
                // setters invalidate internally, so the editor repaints
                // on state change. When the current execution line moves
                // to a new value, center it in the viewport too.
                update = { editor ->
                    editor.breakpointLines = breakpointLines
                    val prev = editor.currentExecutionLine
                    editor.currentExecutionLine = currentLine
                    if (currentLine != null && currentLine != prev) {
                        editor.scrollToLineCentered(currentLine)
                    }
                    applyDiagnostics(editor, lspDiagnostics)
                },
                onRelease = { editor -> editor.release() },
            )
        }
    }
}

/**
 * Converts clangd's [LspDiagnostic] list into a Sora [DiagnosticsContainer]
 * and attaches it to the editor so squiggles render inline and
 * [io.github.rosemoe.sora.widget.component.EditorDiagnosticsTooltipWindow]
 * can show the message when the cursor enters a region.
 *
 * Out-of-range line/column values (stale diagnostics for a newer buffer)
 * are skipped silently — clangd re-emits on every didChange, so the next
 * pass overwrites this one anyway.
 */
private fun applyDiagnostics(
    editor: DebugCodeEditor,
    diagnostics: List<LspDiagnostic>,
) {
    val container = DiagnosticsContainer()
    if (diagnostics.isNotEmpty()) {
        val content = editor.text
        val totalLines = content.lineCount
        for (d in diagnostics) {
            if (d.line < 0 || d.line >= totalLines) continue
            val endLineClamped = d.endLine.coerceIn(d.line, totalLines - 1)
            val startCol = d.column.coerceAtLeast(0)
                .coerceAtMost(content.getColumnCount(d.line))
            val endColClamped = d.endColumn.coerceAtLeast(0)
                .coerceAtMost(content.getColumnCount(endLineClamped))
            val start = runCatching { content.getCharIndex(d.line, startCol) }.getOrNull() ?: continue
            var end = runCatching { content.getCharIndex(endLineClamped, endColClamped) }.getOrNull() ?: continue
            if (end <= start) end = start + 1
            val severity = when (d.severity) {
                LspDiagnostic.Severity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
                LspDiagnostic.Severity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
                LspDiagnostic.Severity.INFORMATION -> DiagnosticRegion.SEVERITY_TYPO
                LspDiagnostic.Severity.HINT -> DiagnosticRegion.SEVERITY_TYPO
            }
            container.addDiagnostic(DiagnosticRegion(start, end, severity))
        }
    }
    editor.diagnostics = container
}
