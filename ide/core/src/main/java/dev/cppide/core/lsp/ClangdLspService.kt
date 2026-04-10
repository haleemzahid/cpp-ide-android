package dev.cppide.core.lsp

import android.util.Log
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.project.Project
import dev.cppide.core.toolchain.Toolchain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.Diagnostic as Lsp4jDiagnostic
import org.eclipse.lsp4j.CompletionCapabilities
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemCapabilities
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * LSP4J-based clangd client. Owns the clangd subprocess, the JSON-RPC
 * launcher, and the per-file diagnostic stream.
 *
 * Threading:
 *  - LSP4J creates its own listener thread for the connection
 *  - We dispatch all blocking I/O on [DispatcherProvider.io]
 *  - Diagnostic notifications arrive on the listener thread; we forward
 *    them via [_diagnostics] StateFlow which is thread-safe
 */
class ClangdLspService(
    private val toolchain: Toolchain,
    private val dispatchers: DispatcherProvider,
) : LspService {

    private val _state = MutableStateFlow<LspState>(LspState.NotStarted)
    override val state: StateFlow<LspState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private var process: Process? = null
    private var server: LanguageServer? = null
    private var currentRoot: File? = null

    override suspend fun start(project: Project): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            Log.i(TAG, "start() called for project ${project.name} at ${project.root}")
            stop()  // make sure we don't leak a previous instance

            val paths = toolchain.paths
                ?: error("Toolchain not installed; install before starting LSP")

            _state.value = LspState.Starting("generating compile_commands.json")
            val ccj = CompileCommandsGenerator.generate(project.root, paths)
            Log.i(TAG, "compile_commands.json -> ${ccj.absolutePath}")

            _state.value = LspState.Starting("launching clangd")
            val cmd = listOf(
                paths.clangd.absolutePath,
                "--background-index=false",
                // Raised from 20 to 100 so short prefixes like `std::c`
                // still include `cin`. At 20 clangd cut it off (cout +
                // char_traits + chars_format + various iterators filled
                // the window first), leaving the popup with no `cin`.
                "--limit-results=100",
                // Ranking model. `decision_forest` is clangd's ML-trained
                // ranker built from real completion-acceptance telemetry;
                // it promotes things people actually pick (cin/cout/cerr
                // for `std::c`) over alphabetical-ish heuristics. The
                // default `heuristics` ranker buries those under
                // `char_traits`, `chars_format`, `common_iterator`, etc.
                "--ranking-model=decision_forest",
                "--header-insertion=never",
                "--clang-tidy=false",
                "--log=error",
                "--compile-commands-dir=${File(project.root, ".cppide").absolutePath}",
            )

            val pb = ProcessBuilder(cmd)
                .directory(project.root)
                .redirectErrorStream(false)
            pb.environment().putAll(paths.processEnv(workingDir = project.root))

            val proc = pb.start()
            process = proc
            Log.i(TAG, "clangd spawned, alive=${proc.isAlive}")

            // Drain stderr on a side coroutine so clangd doesn't block.
            // Wrapped in try/catch because stop() destroys the process and
            // closes the stream mid-read, raising InterruptedIOException
            // that would otherwise crash the whole app.
            scope.launch {
                try {
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { Log.w(TAG, "[clangd] $it") }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "stderr drain ended: ${t.javaClass.simpleName}")
                }
            }

            val client = ClangdLanguageClient()
            val launcher: Launcher<LanguageServer> = Launcher.Builder<LanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(LanguageServer::class.java)
                .setInput(proc.inputStream)
                .setOutput(proc.outputStream)
                .create()

            launcher.startListening()
            val s = launcher.remoteProxy
            server = s
            Log.i(TAG, "LSP4J launcher started")

            _state.value = LspState.Starting("LSP initialize")
            val init = InitializeParams().apply {
                processId = android.os.Process.myPid()
                rootUri = project.root.toURI().toString()
                workspaceFolders = listOf(
                    WorkspaceFolder(project.root.toURI().toString(), project.name)
                )
                capabilities = ClientCapabilities().apply {
                    textDocument = org.eclipse.lsp4j.TextDocumentClientCapabilities().apply {
                        completion = CompletionCapabilities(
                            CompletionItemCapabilities(true)
                        )
                        synchronization = org.eclipse.lsp4j.SynchronizationCapabilities().apply {
                            didSave = true
                            willSave = false
                        }
                        publishDiagnostics = org.eclipse.lsp4j.PublishDiagnosticsCapabilities()
                        hover = org.eclipse.lsp4j.HoverCapabilities()
                    }
                }
            }
            val initResult = s.initialize(init).await()
            Log.i(TAG, "LSP initialize OK, server caps=${initResult.capabilities}")
            s.initialized(InitializedParams())
            Log.i(TAG, "LSP initialized notification sent — Ready")

            _state.value = LspState.Ready(project.root.absolutePath)
            currentRoot = project.root
        }.onFailure { t ->
            Log.e(TAG, "LSP start failed", t)
            _state.value = LspState.Error(t.message ?: "unknown", t)
            cleanupProcess()
        }
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        try {
            server?.shutdown()?.await()
            server?.exit()
        } catch (_: Throwable) {
            // best-effort
        }
        cleanupProcess()
        _state.value = LspState.NotStarted
        _diagnostics.value = emptyMap()
    }

    private fun cleanupProcess() {
        process?.destroyForcibly()
        process = null
        server = null
        currentRoot = null
    }

    // ---- text document sync ----

    override suspend fun didOpen(file: File, languageId: String, content: String) = withContext(dispatchers.io) {
        val s = server
        if (s == null) {
            Log.w(TAG, "didOpen(${file.name}) — server is null, dropping")
            return@withContext
        }
        Log.i(TAG, "didOpen(${file.name}, lang=$languageId, ${content.length} chars)")
        s.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(file.toURI().toString(), languageId, 1, content)
            )
        )
    }

    override suspend fun didChange(file: File, content: String, version: Int) = withContext(dispatchers.io) {
        val s = server
        if (s == null) {
            Log.w(TAG, "didChange(${file.name}) — server is null, dropping")
            return@withContext
        }
        // Log a window of text around the end so we can sanity-check that
        // clangd is actually getting the buffer we think it is. Truncate
        // hard — we don't want every keystroke dumping kilobytes to logcat.
        val tailSnippet = content.takeLast(60).replace("\n", "\\n")
        Log.d(TAG, "didChange(${file.name}) v=$version len=${content.length} tail='…$tailSnippet'")
        s.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(file.toURI().toString(), version),
                listOf(TextDocumentContentChangeEvent(content)),
            )
        )
    }

    override suspend fun didSave(file: File) = withContext(dispatchers.io) {
        val s = server ?: return@withContext
        s.textDocumentService.didSave(
            DidSaveTextDocumentParams(TextDocumentIdentifier(file.toURI().toString()))
        )
    }

    override suspend fun didClose(file: File) = withContext(dispatchers.io) {
        val s = server ?: return@withContext
        s.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(file.toURI().toString()))
        )
    }

    // ---- requests ----

    override suspend fun complete(file: File, line: Int, character: Int): List<LspCompletion> =
        withContext(dispatchers.io) {
            val s = server ?: run {
                Log.w(TAG, "complete(): server is null, returning empty")
                return@withContext emptyList()
            }
            val t0 = System.currentTimeMillis()
            try {
                Log.d(TAG, "complete() -> ${file.name} at $line:$character")
                val params = CompletionParams(
                    TextDocumentIdentifier(file.toURI().toString()),
                    Position(line, character),
                )
                val result = s.textDocumentService.completion(params).await()
                val items: List<CompletionItem> = when {
                    result == null -> emptyList()
                    result.isLeft -> result.left ?: emptyList()
                    result.isRight -> result.right?.items ?: emptyList()
                    else -> emptyList()
                }
                // Leave clangd's own arrival order intact. Re-ranking
                // (prefix bucket + length boost) happens in the UI layer
                // where we know what the user is typing; doing it here
                // throws away information we don't have yet.
                val mapped = items.map { it.toLspCompletion() }
                val dt = System.currentTimeMillis() - t0
                Log.d(
                    TAG,
                    "complete() <- ${mapped.size} items in ${dt}ms; " +
                        "head=${mapped.take(8).map { it.label }}"
                )
                mapped
            } catch (t: Throwable) {
                Log.e(TAG, "completion failed after ${System.currentTimeMillis() - t0}ms", t)
                emptyList()
            }
        }

    override suspend fun hover(file: File, line: Int, character: Int): String? =
        withContext(dispatchers.io) {
            val s = server ?: return@withContext null
            try {
                val hover = s.textDocumentService.hover(
                    HoverParams(
                        TextDocumentIdentifier(file.toURI().toString()),
                        Position(line, character),
                    )
                ).await() ?: return@withContext null
                hover.contents?.let { contents ->
                    when {
                        contents.isLeft -> contents.left?.joinToString("\n") { item ->
                            when {
                                item.isLeft -> item.left ?: ""
                                item.isRight -> item.right?.value ?: ""
                                else -> ""
                            }
                        }
                        contents.isRight -> contents.right?.value
                        else -> null
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "hover failed", t)
                null
            }
        }

    // ---- LanguageClient: receives notifications from clangd ----

    private inner class ClangdLanguageClient : LanguageClient {

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            Log.i(TAG, "publishDiagnostics: ${params.uri} (${params.diagnostics?.size ?: 0} items)")
            val file = try {
                File(java.net.URI(params.uri)).absolutePath
            } catch (_: Throwable) {
                params.uri
            }
            val list: List<LspDiagnostic> = params.diagnostics?.map { d: Lsp4jDiagnostic ->
                val codeStr: String? = d.code?.let { c ->
                    when {
                        c.isLeft -> c.left
                        c.isRight -> c.right?.toString()
                        else -> null
                    }
                }
                LspDiagnostic(
                    fileUri = params.uri,
                    line = d.range?.start?.line ?: 0,
                    column = d.range?.start?.character ?: 0,
                    endLine = d.range?.end?.line ?: 0,
                    endColumn = d.range?.end?.character ?: 0,
                    severity = when (d.severity) {
                        DiagnosticSeverity.Error -> LspDiagnostic.Severity.ERROR
                        DiagnosticSeverity.Warning -> LspDiagnostic.Severity.WARNING
                        DiagnosticSeverity.Information -> LspDiagnostic.Severity.INFORMATION
                        DiagnosticSeverity.Hint -> LspDiagnostic.Severity.HINT
                        null -> LspDiagnostic.Severity.ERROR
                    },
                    message = d.message?.toString() ?: "",
                    source = d.source,
                    code = codeStr,
                )
            } ?: emptyList()
            _diagnostics.update { current -> current + (file to list) }
        }

        override fun showMessage(p0: MessageParams) {
            Log.i(TAG, "[clangd msg ${p0.type}] ${p0.message}")
        }

        override fun showMessageRequest(p0: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
            return CompletableFuture.completedFuture(null)
        }

        override fun logMessage(p0: MessageParams) {
            Log.d(TAG, "[clangd log ${p0.type}] ${p0.message}")
        }

        override fun telemetryEvent(p0: Any?) { /* ignore */ }
    }

    private fun CompletionItem.toLspCompletion(): LspCompletion = LspCompletion(
        // Clangd pads labels with a leading space for VSCode's icon gutter
        // (e.g. " cin" not "cin"). That space breaks prefix matching in
        // sora's popup — trim it here once so nothing downstream has to
        // know about the quirk. Same for insertText defensively.
        label = (label ?: "").trimStart(),
        insertText = (insertText ?: textEdit?.let {
            if (it.isLeft) it.left?.newText ?: label ?: "" else label ?: ""
        } ?: label ?: "").trimStart(),
        detail = detail,
        sortText = sortText,
        documentation = documentation?.let { d ->
            when {
                d.isLeft -> d.left
                d.isRight -> d.right?.value
                else -> null
            }
        },
        kind = when (kind) {
            CompletionItemKind.Variable, CompletionItemKind.Constant, CompletionItemKind.Value ->
                LspCompletion.Kind.VARIABLE
            CompletionItemKind.Function -> LspCompletion.Kind.FUNCTION
            CompletionItemKind.Method, CompletionItemKind.Constructor -> LspCompletion.Kind.METHOD
            CompletionItemKind.Class -> LspCompletion.Kind.CLASS
            CompletionItemKind.Struct -> LspCompletion.Kind.STRUCT
            CompletionItemKind.Enum, CompletionItemKind.EnumMember -> LspCompletion.Kind.ENUM
            CompletionItemKind.Keyword -> LspCompletion.Kind.KEYWORD
            CompletionItemKind.Field, CompletionItemKind.Property -> LspCompletion.Kind.FIELD
            CompletionItemKind.Module -> LspCompletion.Kind.MODULE
            CompletionItemKind.File, CompletionItemKind.Folder -> LspCompletion.Kind.FILE
            CompletionItemKind.Snippet -> LspCompletion.Kind.SNIPPET
            else -> LspCompletion.Kind.OTHER
        },
    )

    companion object {
        private const val TAG = "cppide-lsp"
    }
}
