package dev.cppide.ide.screens.editor.components

import android.os.Bundle
import android.util.Log
import dev.cppide.core.lsp.LspCompletion
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import kotlinx.coroutines.runBlocking

/**
 * Sora [Language] that delegates syntax highlighting, bracket matching,
 * indent handling, and symbol-pair logic to a [TextMateLanguage], but
 * overrides [requireAutoComplete] to fetch real completions from clangd
 * via [fetchCompletions].
 *
 * Kotlin's `by base` delegation forwards every other [Language] method
 * (analyzer, formatter, newline handlers, etc.) to the TextMate instance,
 * so we only need to implement the one method we actually want different.
 *
 * [fetchCompletions] is a `suspend` bridge back to the ViewModel / LSP
 * service. Sora invokes [requireAutoComplete] on its internal completion
 * worker thread (NOT the UI thread), so `runBlocking` here is safe — we
 * are not blocking the main thread.
 */
class LspCppLanguage(
    private val base: TextMateLanguage,
    private val fetchCompletions: suspend (
        liveContent: String,
        line: Int,
        column: Int,
    ) -> List<LspCompletion>,
) : Language by base {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        // How many characters of the current identifier are already typed.
        // Sora deletes this many chars before inserting the selected item.
        val prefix: String = CompletionHelper.computePrefix(content, position) { ch ->
            ch.isLetterOrDigit() || ch == '_'
        } ?: ""
        val prefixLength = prefix.length

        // Pull the live editor content straight from sora's Content, so
        // clangd sees EXACTLY what the user sees — not whatever our Compose
        // state copy happens to have flushed. Cheap: toString() on Content
        // is a char-array copy.
        val liveContent = content.reference.toString()
        val lineText = try { content.getLine(position.line) } catch (_: Throwable) { "<oob>" }
        Log.d(
            TAG,
            "requireAutoComplete pos=${position.line}:${position.column} " +
                "prefix='$prefix' len=${liveContent.length} line='$lineText'"
        )

        val items = try {
            runBlocking { fetchCompletions(liveContent, position.line, position.column) }
        } catch (_: CompletionCancelledException) {
            throw CompletionCancelledException()  // propagate
        } catch (t: Throwable) {
            Log.w(TAG, "fetchCompletions threw", t)
            emptyList()
        }
        Log.i(TAG, "clangd returned ${items.size} items; first=${items.take(5).map { it.label }}")

        // We intentionally do NOT call CompletionHelper.checkCancelled()
        // before publishing. Sora's completion thread uses an internal
        // cancellation flag set on the *previous* thread whenever the
        // user types another char; the PublisherCompletionPublisher.addItem
        // path already checks that flag and silently no-ops on stale
        // threads. Calling it ourselves was eating every result.
        if (items.isEmpty()) {
            Log.i(TAG, "no items to publish")
            return
        }

        try {
            // IMPORTANT: set our own comparator BEFORE addItem calls. The
            // default sora comparator uses fuzzy matching on the label and
            // ignores sortText entirely — so the bucket prefix we assign
            // below only works if we also tell the publisher to sort by
            // sortText. Without this, `cin` for prefix `ci` ends up #8
            // even though its bucket is "0-..." and everything else is
            // "1-...".
            publisher.setComparator(Comparator { a, b ->
                (a.sortText ?: "").compareTo(b.sortText ?: "")
            })

            // Ranking policy:
            //   1. Prefix-matches float above non-prefix-matches ("0-"
            //      bucket vs "1-"). A user typing "ci" almost always
            //      wants cin, not a substring match inside common_iterator.
            //   2. Within the prefix bucket, SHORTER labels win. Clangd
            //      with decision_forest still puts `char_traits` above
            //      `cin` for prefix "c" because the ML model has no
            //      surrounding context. But users typing short prefixes
            //      overwhelmingly want short names: `cin` (3) > `char`
            //      (4) > `class` (5) > `char_traits` (11). Label length
            //      is a dumb but effective proxy for "how fundamental".
            //   3. Ties within a length broken by clangd's own sortText
            //      so its relevance signal still contributes.
            items.forEachIndexed { index, c ->
                val commit = stripSnippetPlaceholders(c.insertText)
                val item = SimpleCompletionItem(c.label, c.detail ?: "", prefixLength, commit)
                    .kind(c.kind.toSoraKind())
                val isPrefixMatch = prefix.isNotEmpty() &&
                    c.label.startsWith(prefix, ignoreCase = true)
                val bucket = if (isPrefixMatch) "0" else "1"
                val lenKey = "%04d".format(c.label.length.coerceAtMost(9999))
                val tail = c.sortText ?: "%06d".format(index)
                item.sortText = "$bucket-$lenKey-$tail"
                publisher.addItem(item)
            }
            // Forced update — bypass sora's internal batch threshold so
            // the popup always refreshes, even for small result sets
            // (e.g. 4 items for prefix `cout`).
            publisher.updateList(true)
            Log.i(TAG, "published ${items.size} items, forced updateList OK")
        } catch (t: CompletionCancelledException) {
            Log.i(TAG, "publish cancelled by sora (stale thread)")
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "publish FAILED: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }
}

/**
 * Clangd sometimes returns snippet-syntax insertText like
 * `push_back(${1:const value_type &__x})`. We don't support snippet
 * placeholder navigation, so collapse them to their default text:
 *   `${1:foo}` → `foo`
 *   `$0`, `$1` → ``
 *
 * NB: the closing `}` MUST be escaped. Android's ICU regex engine treats
 * a bare `}` as a syntax error (unlike J2SE's regex) and throws
 * PatternSyntaxException when compiling. We learned that the hard way —
 * an uncaught throw here took down the entire publish for every result
 * set that contained a snippet item.
 */
private val SNIPPET_PLACEHOLDER_WITH_DEFAULT = Regex("""\$\{\d+:([^}]*)\}""")
private val SNIPPET_PLACEHOLDER_BARE = Regex("""\$\d+""")

private fun stripSnippetPlaceholders(text: String): String {
    if ('$' !in text) return text
    val withDefaults = SNIPPET_PLACEHOLDER_WITH_DEFAULT.replace(text) { it.groupValues[1] }
    return SNIPPET_PLACEHOLDER_BARE.replace(withDefaults, "")
}

private const val TAG = "cppide-complete"

private fun LspCompletion.Kind.toSoraKind(): CompletionItemKind = when (this) {
    LspCompletion.Kind.VARIABLE -> CompletionItemKind.Variable
    LspCompletion.Kind.FUNCTION -> CompletionItemKind.Function
    LspCompletion.Kind.METHOD -> CompletionItemKind.Method
    LspCompletion.Kind.CLASS -> CompletionItemKind.Class
    LspCompletion.Kind.STRUCT -> CompletionItemKind.Struct
    LspCompletion.Kind.ENUM -> CompletionItemKind.Enum
    LspCompletion.Kind.KEYWORD -> CompletionItemKind.Keyword
    LspCompletion.Kind.FIELD -> CompletionItemKind.Field
    LspCompletion.Kind.MODULE -> CompletionItemKind.Module
    LspCompletion.Kind.FILE -> CompletionItemKind.File
    LspCompletion.Kind.SNIPPET -> CompletionItemKind.Snippet
    LspCompletion.Kind.OTHER -> CompletionItemKind.Text
}
