package dev.cppide.ide.screens.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import dev.cppide.ide.screens.editor.TerminalLine
import dev.cppide.ide.theme.CppIde

/**
 * True-inline terminal: one continuous monospace stream where the
 * cursor sits flush against the last output character, even when the
 * program's last write didn't end in '\n' (e.g. after `cout << "Name: "`).
 *
 * Implementation: we split terminal content into
 *  1. *completed* lines — everything up to the last '\n', rendered
 *     as a color-annotated Text block above;
 *  2. a *partial* tail — any bytes after the last '\n' that the
 *     program emitted without terminating, rendered as a read-only
 *     prefix VisualTransformation in front of the input field.
 *
 * The BasicTextField only *owns* the user's typed characters; the
 * transformation paints the partial output in front of them so the
 * caret appears inline. Enter submits the owned text (prefix stays
 * put — it's the program's output, not user input).
 */
@Composable
fun TerminalView(
    lines: List<TerminalLine>,
    inputEnabled: Boolean,
    /**
     * Whether the terminal is allowed to pop the soft keyboard on its
     * own when a partial prompt appears. Passed `false` while the
     * debugger is paused (`Stopped`) — the partial prompt visible on
     * screen is a side-effect of the user having *stepped over* the
     * `cout` that wrote it, but the program isn't actually blocked on
     * stdin yet. Auto-showing the keyboard there is confusing: the
     * user sees the IME pop, hasn't asked to type anything, and has
     * to dismiss it before continuing to step. Manual tap on the
     * terminal still opens the keyboard regardless.
     */
    autoShowKeyboard: Boolean,
    onSendInput: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var pending by remember { mutableStateOf(TextFieldValue("")) }

    // Partition the stream: every line that ends in '\n' is "completed"
    // output rendered above the cursor; the final un-terminated run of
    // bytes (if any) becomes a prefix on the input field so the caret
    // lands right after it.
    val split = remember(lines) { splitCompletedAndPartial(lines) }

    // Show the input field whenever the program is accepting input.
    //
    // We used to gate this on "a partial prompt is visible" (i.e. the
    // last output didn't end in '\n'), on the theory that the field
    // should only appear when the program is *actually* waiting for
    // something. That heuristic broke two back-to-back `cin`s with no
    // intervening `cout`: we echo the user's submitted line back into
    // the terminal as "5\n", which terminates the partial, so the
    // field disappeared — and nothing on the second `cin` brought it
    // back, because the second cin produces no new stdout. Always-on
    // while `inputEnabled` is the simplest correct rule; during a
    // compute-only phase the cursor just sits idle at the bottom.
    val showInput = inputEnabled

    // Auto-focus + show IME when input opens AND the caller has opted
    // in to auto-show. When input is disabled (e.g. the debugger just
    // hit a breakpoint), actively hide the IME so a keyboard opened a
    // moment earlier by a brief Running window during a step doesn't
    // linger over a now-inert input field. Clear the pending buffer
    // on disable too so stale text doesn't persist to the next run.
    LaunchedEffect(showInput, inputEnabled, autoShowKeyboard) {
        if (showInput && autoShowKeyboard) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        if (!inputEnabled) {
            keyboard?.hide()
            pending = TextFieldValue("")
        }
    }

    LaunchedEffect(lines.size, pending.text) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    // Output block: always includes completed lines. When the input
    // field is hidden but there's a partial (e.g. during stepping
    // before the user has typed), append the partial inline so it
    // stays visible.
    //
    // Newline handling: each completed line is separated from the next
    // by exactly one '\n'. If a source line already ends in '\n' we
    // don't add another; if it doesn't, we insert one. Crucially, we
    // *don't* leave a trailing '\n' on the final character of the
    // rendered block — that would render as a visible empty line below
    // the last output, pushing the input field one row further down
    // and producing the "empty line between last value and cursor"
    // the user reported.
    val outputAnnotated = remember(split, colors, showInput) {
        buildAnnotatedString {
            val last = split.completed.lastIndex
            split.completed.forEachIndexed { idx, line ->
                withStyle(SpanStyle(color = line.color(colors))) {
                    val text = line.text
                    if (idx == last) {
                        // Drop at most one trailing '\n' on the final
                        // line so the Text block doesn't end with a
                        // blank row before the input field.
                        if (text.endsWith('\n')) append(text.dropLast(1))
                        else append(text)
                    } else {
                        append(text)
                        if (!text.endsWith('\n')) append('\n')
                    }
                }
            }
            if (!showInput && split.partial != null) {
                val p = split.partial
                if (split.completed.isNotEmpty()) append('\n')
                withStyle(SpanStyle(color = p.color(colors))) { append(p.text) }
            }
        }
    }

    val partialColor = split.partial?.color(colors) ?: colors.textPrimary

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scrollState)
            .padding(horizontal = dimens.spacingM, vertical = dimens.spacingS)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = inputEnabled,
            ) {
                focusRequester.requestFocus()
                keyboard?.show()
            },
    ) {
        if (outputAnnotated.isNotEmpty()) {
            Text(
                text = outputAnnotated,
                style = CppIde.typography.codeSmall,
                color = colors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showInput) {
            val prefixText = split.partial?.text.orEmpty()
            BasicTextField(
            value = pending,
            onValueChange = { new ->
                if (!inputEnabled) return@BasicTextField
                val text = new.text
                val nl = text.indexOf('\n')
                if (nl < 0) {
                    pending = new
                    return@BasicTextField
                }
                val submit = text.substring(0, nl)
                val rest = text.substring(nl + 1)
                pending = TextFieldValue(rest, TextRange(rest.length))
                onSendInput(submit)
                // A second `cin` right after this one won't change
                // `showInput` (the partial prompt is still non-null), so
                // the LaunchedEffect above won't re-fire. If the IME
                // auto-hid on Enter, the user would be stuck looking at
                // a dead-looking terminal. Proactively re-request focus
                // so the keyboard stays available for the next line.
                if (autoShowKeyboard) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            },
            enabled = inputEnabled,
            singleLine = false,
            textStyle = CppIde.typography.codeSmall.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(
                if (inputEnabled) colors.accent else Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    val text = pending.text
                    pending = TextFieldValue("")
                    onSendInput(text)
                    if (autoShowKeyboard) {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                },
            ),
            visualTransformation = PartialPrefixTransformation(
                prefix = prefixText,
                prefixColor = partialColor,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            )
        }
    }
}

private data class Split(
    val completed: List<TerminalLine>,
    /**
     * The last emitted line if it didn't terminate with '\n'; rendered
     * as a read-only prefix on the input field so the caret appears
     * inline after it.
     */
    val partial: TerminalLine?,
)

private fun splitCompletedAndPartial(lines: List<TerminalLine>): Split {
    if (lines.isEmpty()) return Split(emptyList(), null)
    val last = lines.last()
    return if (last.text.endsWith('\n')) {
        Split(lines, null)
    } else {
        Split(lines.dropLast(1), last)
    }
}

private fun TerminalLine.color(colors: dev.cppide.ide.theme.CppIdeColors): Color = when (this) {
    is TerminalLine.Stdout -> colors.textPrimary
    is TerminalLine.Stderr -> colors.diagnosticWarning
    is TerminalLine.Info -> colors.accent
    is TerminalLine.Error -> colors.diagnosticError
}

/**
 * Renders [prefix] as an un-editable, colored chunk in front of the
 * user's typed text. The OffsetMapping shifts cursor positions by the
 * prefix length so the caret always sits over *user* characters, not
 * inside the program's partial output.
 */
private class PartialPrefixTransformation(
    private val prefix: String,
    private val prefixColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (prefix.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val combined = buildAnnotatedString {
            withStyle(SpanStyle(color = prefixColor)) { append(prefix) }
            append(text)
        }
        val shift = prefix.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + shift
            override fun transformedToOriginal(offset: Int): Int =
                (offset - shift).coerceAtLeast(0)
        }
        return TransformedText(combined, mapping)
    }
}
