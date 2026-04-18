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

    // Show the input only when the program is waiting for it. Heuristic:
    // the last output didn't end in '\n', so it's a `cout << "prompt: "`
    // style prompt. This keeps the field hidden while the program is just
    // running (Run) or stepping through code (Debug) — it pops in right
    // as the prompt appears and goes away once the user submits.
    val showInput = inputEnabled && split.partial != null

    // Auto-focus + show IME when the prompt first appears; clear the
    // buffer when the run ends.
    LaunchedEffect(showInput, inputEnabled) {
        if (showInput) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        if (!inputEnabled) {
            pending = TextFieldValue("")
        }
    }

    LaunchedEffect(lines.size, pending.text) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    // Output block: always includes completed lines. When the input field
    // is hidden but there's a partial (e.g. during stepping before the
    // user has typed), append the partial inline so it stays visible.
    val outputAnnotated = remember(split, colors, showInput) {
        buildAnnotatedString {
            split.completed.forEach { line ->
                withStyle(SpanStyle(color = line.color(colors))) {
                    append(line.text)
                    if (!line.text.endsWith('\n')) append('\n')
                }
            }
            if (!showInput && split.partial != null) {
                val p = split.partial
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
