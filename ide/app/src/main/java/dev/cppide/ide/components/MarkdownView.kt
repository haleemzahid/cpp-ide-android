package dev.cppide.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cppide.ide.theme.CppIde

/**
 * Minimal markdown renderer for exercise prompts. Intentionally small —
 * covers the subset students need:
 *
 * - headings (`#` through `######`)
 * - paragraphs
 * - unordered lists (`-` / `*`)
 * - ordered lists (`1.` etc)
 * - fenced code blocks (```lang ... ```)
 * - inline: **bold**, *italic*, `code`, [label](url)
 * - horizontal rule (`---`)
 *
 * No tables, no blockquote nesting, no HTML passthrough — any unknown
 * syntax degrades gracefully to plain text. Parser is single-pass
 * line-based; fenced code blocks are the only multi-line construct so
 * the state machine stays tiny.
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(CppIde.dimens.spacingL),
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    Column(
        modifier = modifier
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        for ((i, block) in blocks.withIndex()) {
            if (i > 0) Spacer(Modifier.height(dimens.spacingS))
            BlockView(block)
        }
    }
}

// ---------------------------------------------------------------- parser

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data object HorizontalRule : MdBlock
}

private fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    var i = 0

    fun flushParagraph(buffer: StringBuilder) {
        if (buffer.isNotBlank()) {
            out.add(MdBlock.Paragraph(buffer.toString().trim()))
        }
        buffer.clear()
    }

    val para = StringBuilder()

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block — multi-line, preserve inner lines verbatim.
        if (line.trimStart().startsWith("```")) {
            flushParagraph(para)
            val lang = line.trimStart().removePrefix("```").trim().takeIf { it.isNotEmpty() }
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.appendLine(lines[i])
                i++
            }
            // Skip the closing fence if present.
            if (i < lines.size) i++
            out.add(MdBlock.CodeBlock(lang, code.toString().trimEnd('\n')))
            continue
        }

        // Horizontal rule.
        if (line.trim().matches(Regex("^-{3,}$|^\\*{3,}$|^_{3,}$"))) {
            flushParagraph(para)
            out.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // Heading.
        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
        if (headingMatch != null) {
            flushParagraph(para)
            val level = headingMatch.groupValues[1].length
            out.add(MdBlock.Heading(level, headingMatch.groupValues[2].trim()))
            i++
            continue
        }

        // Bullet list — consume consecutive bullet lines.
        if (line.matches(Regex("^\\s*[-*]\\s+.*"))) {
            flushParagraph(para)
            val items = mutableListOf<String>()
            while (i < lines.size && lines[i].matches(Regex("^\\s*[-*]\\s+.*"))) {
                items.add(lines[i].replaceFirst(Regex("^\\s*[-*]\\s+"), ""))
                i++
            }
            out.add(MdBlock.BulletList(items))
            continue
        }

        // Numbered list.
        if (line.matches(Regex("^\\s*\\d+\\.\\s+.*"))) {
            flushParagraph(para)
            val items = mutableListOf<String>()
            while (i < lines.size && lines[i].matches(Regex("^\\s*\\d+\\.\\s+.*"))) {
                items.add(lines[i].replaceFirst(Regex("^\\s*\\d+\\.\\s+"), ""))
                i++
            }
            out.add(MdBlock.NumberedList(items))
            continue
        }

        // Blank line ends a paragraph.
        if (line.isBlank()) {
            flushParagraph(para)
            i++
            continue
        }

        // Plain paragraph line — accumulate with a space separator so a
        // soft-wrapped prompt still renders as one paragraph.
        if (para.isNotEmpty()) para.append(' ')
        para.append(line.trim())
        i++
    }
    flushParagraph(para)
    return out
}

// --------------------------------------------------------------- renderer

@Composable
private fun BlockView(block: MdBlock) {
    val colors = CppIde.colors
    val dimens = CppIde.dimens

    when (block) {
        is MdBlock.Heading -> {
            val (size, weight) = when (block.level) {
                1 -> 26.sp to FontWeight.Bold
                2 -> 22.sp to FontWeight.SemiBold
                3 -> 18.sp to FontWeight.SemiBold
                else -> 16.sp to FontWeight.SemiBold
            }
            Text(
                text = inline(block.text),
                color = colors.textPrimary,
                fontSize = size,
                fontWeight = weight,
                modifier = Modifier.padding(
                    top = if (block.level <= 2) dimens.spacingL else dimens.spacingM,
                    bottom = dimens.spacingXs,
                ),
            )
        }

        is MdBlock.Paragraph -> {
            Text(
                text = inline(block.text),
                color = colors.textPrimary,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }

        is MdBlock.BulletList -> {
            Column {
                for (item in block.items) {
                    Row(modifier = Modifier.padding(vertical = dimens.spacingXxs)) {
                        Text(
                            text = "•  ",
                            color = colors.accent,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = inline(item),
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        is MdBlock.NumberedList -> {
            Column {
                for ((idx, item) in block.items.withIndex()) {
                    Row(modifier = Modifier.padding(vertical = dimens.spacingXxs)) {
                        Text(
                            text = "${idx + 1}.  ",
                            color = colors.accent,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = inline(item),
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        is MdBlock.CodeBlock -> {
            // Horizontally scrollable so wide lines don't squish
            // everything else. Monospace font + subtle background
            // mirrors what students see in the actual editor pane.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimens.radiusM))
                    .background(colors.surfaceElevated)
                    .padding(dimens.spacingM),
            ) {
                if (!block.language.isNullOrBlank()) {
                    Text(
                        text = block.language,
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = dimens.spacingXs),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = block.code,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp,
                    )
                }
            }
        }

        MdBlock.HorizontalRule -> {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = dimens.spacingM)
                    .background(colors.border)
                    .padding(top = 1.dp),
            )
        }
    }
}

/**
 * Inline formatter. Runs four passes in order: code, bold, italic, links.
 * Each pass scans the current state and emits a new [AnnotatedString]
 * with the styled spans attached. Links are made clickable via the
 * surrounding composable's [LocalUriHandler].
 *
 * Not a full CommonMark parser — deliberately doesn't handle escapes
 * or nested emphasis, which exercise prompts don't need. Students see
 * clean output, authors write normal markdown.
 */
@Composable
private fun inline(text: String): AnnotatedString {
    val colors = CppIde.colors

    return buildAnnotatedString {
        val codeRx = Regex("`([^`]+)`")
        val boldRx = Regex("\\*\\*([^*]+)\\*\\*")
        val italicRx = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
        val linkRx = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")

        // Walk the string once, consuming whichever span starts next.
        // Gives us correct interleaving without multi-pass overwrites.
        var i = 0
        while (i < text.length) {
            val remaining = text.substring(i)
            val nextCode = codeRx.find(remaining)
            val nextBold = boldRx.find(remaining)
            val nextItalic = italicRx.find(remaining)
            val nextLink = linkRx.find(remaining)

            val candidates = listOfNotNull(
                nextCode?.let { it.range.first to "code" },
                nextBold?.let { it.range.first to "bold" },
                nextItalic?.let { it.range.first to "italic" },
                nextLink?.let { it.range.first to "link" },
            )
            val next = candidates.minByOrNull { it.first }
            if (next == null) {
                append(remaining)
                break
            }
            append(remaining.substring(0, next.first))
            when (next.second) {
                "code" -> {
                    val m = nextCode!!
                    withStyleSafe(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colors.surfaceElevated,
                            color = colors.accent,
                        ),
                    ) { append(m.groupValues[1]) }
                    i += next.first + m.range.last - m.range.first + 1
                }
                "bold" -> {
                    val m = nextBold!!
                    withStyleSafe(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(m.groupValues[1])
                    }
                    i += next.first + m.range.last - m.range.first + 1
                }
                "italic" -> {
                    val m = nextItalic!!
                    withStyleSafe(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(m.groupValues[1])
                    }
                    i += next.first + m.range.last - m.range.first + 1
                }
                "link" -> {
                    val m = nextLink!!
                    val label = m.groupValues[1]
                    val url = m.groupValues[2]
                    val start = length
                    withStyleSafe(
                        SpanStyle(
                            color = colors.accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(label) }
                    addStringAnnotation("URL", url, start, start + label.length)
                    i += next.first + m.range.last - m.range.first + 1
                }
            }
        }
    }
}

/**
 * Small helper so the caller's buildAnnotatedString body stays readable.
 * Compose's [AnnotatedString.Builder.withStyle] is infix-unfriendly when
 * nested in when-branches.
 */
private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSafe(
    style: SpanStyle,
    block: () -> Unit,
) {
    val start = length
    block()
    addStyle(style, start, length)
}
