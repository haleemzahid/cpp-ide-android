package dev.cppide.core.debug

import android.util.Log
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.toolchain.Toolchain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * [DebuggerService] backed by `lldb-dap`, LLVM's DAP adapter.
 *
 * Architecture:
 *
 *   EditorViewModel
 *        │ suspend calls
 *        ▼
 *   LldbDapDebuggerService       ──── owns ────▶ DapConnection
 *        │                                          │ writes requests
 *        │ observes events                          │ reads responses/events
 *        │                                          ▼
 *        ▼                                      [ lldb-dap process ]
 *   StateFlow<DebuggerState>                        │ stdin/stdout
 *   StateFlow<Map<SrcBp, BpState>>                  ▼
 *   SharedFlow<String>  (inferior stdout/stderr)  target binary
 *
 * The service is stateless between sessions except for breakpoints,
 * which persist so the user's gutter dots survive `start` / `stop`.
 *
 * Threading: all suspend methods are safe to call from any dispatcher.
 * A single internal event-processing coroutine handles DAP event
 * dispatch so state mutations are serialized.
 */
class LldbDapDebuggerService(
    private val toolchain: Toolchain,
    private val workingDir: File,
    private val dispatchers: DispatcherProvider,
) : DebuggerService {

    // ---- public state ----

    private val _state = MutableStateFlow<DebuggerState>(DebuggerState.Idle)
    override val state: StateFlow<DebuggerState> = _state.asStateFlow()

    private val _breakpoints =
        MutableStateFlow<Map<SourceBreakpoint, BreakpointState>>(emptyMap())
    override val breakpoints: StateFlow<Map<SourceBreakpoint, BreakpointState>> =
        _breakpoints.asStateFlow()

    private val _output = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    override val output: SharedFlow<String> = _output.asSharedFlow()

    // ---- session state (null when idle) ----

    private data class Session(
        val process: Process,
        val conn: DapConnection,
        val scope: CoroutineScope,
    )

    private var session: Session? = null
    private val sessionLock = Mutex()

    /**
     * DAP threadId of the thread we step/continue/pause on. lldb-dap
     * emits `thread` and `stopped` events with thread ids; we remember
     * the last one we stopped on so step/continue/pause can target it.
     */
    @Volatile private var currentThreadId: Int = -1

    /**
     * Number of stops in this session that we've silently continued
     * past because they were inside the trampoline / loader / libc.
     * We cap at [MAX_AUTO_CONTINUE_HOPS] to avoid an infinite loop if
     * every stop is in non-user code.
     */
    @Volatile private var autoContinueHops: Int = 0

    /**
     * True once the inferior has reached *user* code at least once in
     * this session, signaled by the first stop with reason=BREAKPOINT.
     * Until this is true, every other stop (signal, exception, step,
     * pause from the loader/trampoline) is silently continued past so
     * the user never sees a stop they don't care about.
     */
    @Volatile private var userCodeReached: Boolean = false

    /**
     * Canonical absolute path of the project root for the current
     * session, with a trailing separator. Stops whose top-frame
     * source file doesn't start with this prefix are treated as
     * "non-user code" and silently auto-continued — that includes
     * the runtime shim, trampoline, libc, the loader, and STL
     * headers. Set in [start], cleared in [shutdownSession].
     */
    @Volatile private var projectRootPrefix: String? = null

    // ---- DebuggerService: lifecycle ----

    override suspend fun start(
        trampolineBinary: File,
        userLibrary: File,
        projectRoot: File,
    ): Result<Unit> = withContext(dispatchers.io) {
        sessionLock.withLock {
            if (session != null) {
                return@withLock Result.failure(IllegalStateException("session already active"))
            }
            runCatching { startLocked(trampolineBinary, userLibrary, projectRoot) }
                .onFailure { t ->
                    Log.e(TAG, "start failed", t)
                    _state.value = DebuggerState.Failed(t.message ?: t.javaClass.simpleName)
                    shutdownSession()
                }
        }
    }

    private suspend fun startLocked(
        trampolineBinary: File,
        userLibrary: File,
        projectRoot: File,
    ) {
        // Canonicalize + ensure trailing separator so prefix matching
        // is correct (e.g. "/foo/bar" must not match "/foo/barbaz").
        projectRootPrefix = runCatching {
            projectRoot.canonicalFile.absolutePath.trimEnd('/') + "/"
        }.getOrNull()
        Log.i(TAG, "start: projectRootPrefix=$projectRootPrefix")
        val paths = toolchain.paths
            ?: error("toolchain not ready")
        val lldbDap = paths.lldbDap
        if (!lldbDap.exists()) {
            error("lldb-dap missing at ${lldbDap.absolutePath}")
        }

        _state.value = DebuggerState.Starting("spawning lldb-dap")

        // Spawn lldb-dap with stdin/stdout as the DAP transport and a
        // separate stderr for diagnostic logging.
        val env = paths.processEnv(workingDir)
        val pb = ProcessBuilder(lldbDap.absolutePath)
            .directory(paths.termuxRoot)
            .redirectErrorStream(false)
        pb.environment().clear()
        pb.environment().putAll(env)
        val proc = pb.start()

        val conn = DapConnection(proc.inputStream, proc.outputStream)
        conn.start()

        val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val newSession = Session(process = proc, conn = conn, scope = sessionScope)
        session = newSession

        // stderr drainer — logs lldb-dap diagnostics to logcat and to
        // [output] so they show up in the user's terminal panel.
        sessionScope.launch {
            try {
                BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))
                    .use { r ->
                        while (isActive) {
                            val line = r.readLine() ?: break
                            Log.i(TAG, "lldb-dap[stderr] $line")
                            _output.tryEmit("[lldb-dap] $line\n")
                        }
                    }
            } catch (_: Throwable) {
                // stream closed on process exit — normal
            }
        }

        // Event processor — routes DAP events into state/output/bp changes.
        sessionScope.launch {
            conn.events.collect { event -> handleEvent(event) }
        }

        // ---- DAP handshake ----

        _state.value = DebuggerState.Starting("initializing")
        val initResp = conn.sendRequest(
            command = "initialize",
            arguments = JSONObject().apply {
                put("clientID", "cppide")
                put("adapterID", "lldb-dap")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("pathFormat", "path")
                put("supportsRunInTerminalRequest", false)
                put("supportsVariableType", true)
                put("supportsVariablePaging", false)
                put("supportsMemoryReferences", true)
                put("locale", "en")
            },
        )
        require(initResp.optBoolean("success")) {
            "initialize failed: ${initResp.optString("message")}"
        }

        // ---- launch ----
        //
        // stopOnEntry is deliberately FALSE. With our trampoline
        // architecture, "entry" lands inside ld-android.so before
        // anyone's user code is loaded — pressing Continue from there
        // walks through the loader, then the trampoline's own
        // initialization, then finally main(). Multiple Continue
        // presses before you see anything useful.
        //
        // Instead, we use lldb's `--pending` flag in preRunCommands to
        // create a pending breakpoint on the symbol `main` in the
        // user's library. Pending means "keep trying to resolve this
        // on every shared-library load until something binds". When
        // the trampoline dlopen's libuser-debug.so, lldb sees the new
        // module, finds main in it, and the breakpoint fires —
        // landing the user directly on their main() with no Continue
        // dance. This matches what VSCode's "stop on entry: false +
        // entrypoint at main" behavior produces on desktop.
        //
        // We restrict to `libuser-debug.so` via --shlib so the bp
        // can't bind to the trampoline's own main() (which has no
        // debug info and isn't useful to stop in).
        val userSoBasename = userLibrary.name
        _state.value = DebuggerState.Starting("launching inferior")
        val launchResp = conn.sendRequest(
            command = "launch",
            arguments = JSONObject().apply {
                put("program", trampolineBinary.absolutePath)
                put("args", JSONArray().apply {
                    put(userLibrary.absolutePath)
                })
                put("cwd", workingDir.absolutePath)
                put("stopOnEntry", false)
                put("env", JSONArray().apply {
                    // Forward our full toolchain env so the inferior
                    // can find libc++_shared.so and friends.
                    for ((k, v) in paths.processEnv(workingDir)) {
                        put("$k=$v")
                    }
                })
                // Send raw lldb commands at multiple lifecycle points to
                // maximize the chance one of them lands. lldb keeps
                // unresolved name-based breakpoints alive automatically
                // and re-resolves them on shared-library load, so a
                // simple `breakpoint set --name main` should bind once
                // libuser-debug.so is dlopen'd by the trampoline.
                put("initCommands", JSONArray().apply {
                    put("settings set target.inline-breakpoint-strategy always")
                })
                // BuildConfig.wrapMain=true preprocesses the user's
                // `int main()` to `int user_main_fn()` via `-Dmain=user_main_fn`,
                // so there is no symbol literally named `main` in
                // libuser-debug.so. The bp has to target the wrapped name.
                put("preRunCommands", JSONArray().apply {
                    put("breakpoint set --name user_main_fn")
                })
            },
            timeoutMs = 60_000,
        )
        Log.i(TAG, "launch response: success=${launchResp.optBoolean("success")} " +
            "message=${launchResp.optString("message", "")} " +
            "body=${launchResp.optJSONObject("body")?.toString()?.take(500)}")
        require(launchResp.optBoolean("success")) {
            "launch failed: ${launchResp.optString("message")}"
        }

        // The server emits an `initialized` event after the launch
        // response; we wait for it, then push breakpoints and
        // configurationDone. In practice lldb-dap emits the event
        // quickly enough that we don't need an explicit wait — the
        // event handler will do setBreakpoints when it fires. But we
        // send configurationDone immediately after any pre-session
        // breakpoints have been replayed. The event handler coordinates
        // this by tracking whether the session has seen `initialized`.
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        sessionLock.withLock {
            val s = session ?: return@withLock
            try {
                s.conn.sendRequest(
                    command = "disconnect",
                    arguments = JSONObject().apply {
                        put("terminateDebuggee", true)
                    },
                    timeoutMs = 3_000,
                )
            } catch (_: Throwable) {
                // best-effort — server may already be dead
            }
            shutdownSession()
            _state.value = DebuggerState.Idle
        }
    }

    /** Tears down the current session's process + scope. Caller holds [sessionLock]. */
    private fun shutdownSession() {
        val s = session ?: return
        session = null
        currentThreadId = -1
        autoContinueHops = 0
        userCodeReached = false
        projectRootPrefix = null
        try { s.conn.close() } catch (_: Throwable) {}
        try {
            if (s.process.isAlive) {
                s.process.destroy()
                // Best-effort graceful, then forcible.
                Thread.sleep(150)
                if (s.process.isAlive) s.process.destroyForcibly()
            }
        } catch (_: Throwable) {}
        try { s.scope.cancel() } catch (_: Throwable) {}
    }

    // ---- DebuggerService: step / continue / pause ----

    override suspend fun stepOver(): Unit = doThreadCommand("next")
    override suspend fun stepInto(): Unit = doThreadCommand("stepIn")
    override suspend fun stepOut(): Unit = doThreadCommand("stepOut")
    override suspend fun cont(): Unit = doThreadCommand("continue")
    override suspend fun pause(): Unit = doThreadCommand("pause")

    private suspend fun doThreadCommand(command: String): Unit =
        withContext(dispatchers.io) {
            val s = session ?: run {
                Log.w(TAG, "$command with no active session")
                return@withContext
            }
            val tid = currentThreadId.takeIf { it > 0 } ?: run {
                Log.w(TAG, "$command but no current thread id")
                return@withContext
            }
            val resp = s.conn.sendRequest(
                command = command,
                arguments = JSONObject().apply { put("threadId", tid) },
            )
            if (!resp.optBoolean("success")) {
                Log.w(TAG, "$command failed: ${resp.optString("message")}")
            }
        }

    // ---- DebuggerService: breakpoints ----

    override suspend fun toggleBreakpoint(file: String, line: Int) {
        val bp = SourceBreakpoint(filePath = file, line = line)
        val current = _breakpoints.value
        val updated = current.toMutableMap()
        if (current.containsKey(bp)) {
            updated.remove(bp)
        } else {
            updated[bp] = BreakpointState(source = bp)
        }
        _breakpoints.value = updated
        syncBreakpointsForFile(file)
    }

    override suspend fun clearBreakpoints() {
        val files = _breakpoints.value.keys.map { it.filePath }.toSet()
        _breakpoints.value = emptyMap()
        files.forEach { syncBreakpointsForFile(it) }
    }

    /**
     * Rebuilds and re-sends the breakpoint set for [filePath] to the
     * active DAP session. If idle, the in-memory map is enough —
     * breakpoints are replayed automatically when the next session
     * starts (via [replayBreakpointsOnInitialized]).
     */
    private suspend fun syncBreakpointsForFile(filePath: String) {
        val s = session ?: return
        val entries = _breakpoints.value.filterKeys { it.filePath == filePath }

        val args = JSONObject().apply {
            put("source", JSONObject().apply {
                put("path", filePath)
                put("name", filePath.substringAfterLast('/'))
            })
            put("breakpoints", JSONArray().apply {
                for ((bp, _) in entries) {
                    put(JSONObject().apply { put("line", bp.line) })
                }
            })
            put("sourceModified", false)
        }

        val resp = try {
            s.conn.sendRequest(command = "setBreakpoints", arguments = args)
        } catch (t: Throwable) {
            Log.w(TAG, "setBreakpoints($filePath) failed", t)
            return
        }

        if (!resp.optBoolean("success")) {
            Log.w(TAG, "setBreakpoints($filePath) returned success=false: ${resp.optString("message")}")
            return
        }

        // Update each entry's verified flag from the response.
        val body = resp.optJSONObject("body") ?: return
        val arr = body.optJSONArray("breakpoints") ?: return
        val sortedEntries = entries.entries.sortedBy { it.key.line }
        val newMap = _breakpoints.value.toMutableMap()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val existing = sortedEntries.getOrNull(i) ?: continue
            val verified = item.optBoolean("verified", false)
            val message = item.optStringOrNull("message")
            // If the server relocated the breakpoint to a nearby line,
            // honor that — lldb often adjusts by 1 when the exact line
            // has no code.
            val adjustedLine = if (item.has("line")) item.optInt("line") else existing.key.line
            val newKey = if (adjustedLine != existing.key.line) {
                SourceBreakpoint(filePath = existing.key.filePath, line = adjustedLine)
            } else {
                existing.key
            }
            newMap.remove(existing.key)
            newMap[newKey] = BreakpointState(
                source = newKey,
                verified = verified,
                message = message,
            )
        }
        _breakpoints.value = newMap
    }

    /**
     * Called after the `initialized` event. Replays all saved source
     * breakpoints, plus a function breakpoint on `main` so the first
     * stop is always at the user's entry point (regardless of whether
     * the user set any source breakpoints).
     */
    private suspend fun replayBreakpointsOnInitialized() {
        val byFile = _breakpoints.value.keys.groupBy { it.filePath }
        for (file in byFile.keys) {
            syncBreakpointsForFile(file)
        }
        val s = session ?: return

        // Always stop at main on the first reach. Without this, the
        // inferior runs from program entry through the loader and the
        // trampoline before hitting main, and the user has no useful
        // first stop. lldb-dap supports `setFunctionBreakpoints` even
        // when no other breakpoints are set.
        try {
            s.conn.sendRequest(
                command = "setFunctionBreakpoints",
                arguments = JSONObject().apply {
                    put("breakpoints", JSONArray().apply {
                        put(JSONObject().apply { put("name", "main") })
                    })
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setFunctionBreakpoints(main) failed", t)
        }

        try {
            s.conn.sendRequest(
                command = "configurationDone",
                arguments = JSONObject(),
                timeoutMs = 10_000,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "configurationDone failed", t)
        }
    }

    // ---- DebuggerService: stack / scopes / variables ----

    override suspend fun fetchScopes(frameId: Int): Result<List<Scope>> =
        withContext(dispatchers.io) {
            runCatching {
                val s = session ?: error("no active session")
                val resp = s.conn.sendRequest(
                    command = "scopes",
                    arguments = JSONObject().apply { put("frameId", frameId) },
                )
                require(resp.optBoolean("success")) { resp.optString("message") }
                val body = resp.optJSONObject("body") ?: return@runCatching emptyList()
                val arr = body.optJSONArray("scopes") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        add(
                            Scope(
                                name = item.optString("name", "?"),
                                variablesReference = item.optInt("variablesReference", 0),
                                expensive = item.optBoolean("expensive", false),
                            )
                        )
                    }
                }
            }
        }

    override suspend fun fetchVariables(variablesReference: Int): Result<List<Variable>> =
        withContext(dispatchers.io) {
            runCatching {
                if (variablesReference <= 0) return@runCatching emptyList()
                val s = session ?: error("no active session")
                val resp = s.conn.sendRequest(
                    command = "variables",
                    arguments = JSONObject().apply { put("variablesReference", variablesReference) },
                )
                require(resp.optBoolean("success")) { resp.optString("message") }
                val body = resp.optJSONObject("body") ?: return@runCatching emptyList()
                val arr = body.optJSONArray("variables") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        add(
                            Variable(
                                name = item.optString("name", "?"),
                                value = item.optString("value", ""),
                                type = item.optStringOrNull("type"),
                                variablesReference = item.optInt("variablesReference", 0),
                                memoryReference = item.optStringOrNull("memoryReference"),
                            )
                        )
                    }
                }
            }
        }

    // ---- DAP event handling ----

    private suspend fun handleEvent(event: JSONObject) {
        // Verbose: log every DAP event we receive. Helps diagnose
        // launch / breakpoint resolution issues.
        Log.i(TAG, "event ${event.optString("event")}: ${event.optJSONObject("body")?.toString()?.take(400)}")
        when (val name = event.optString("event")) {
            "initialized" -> {
                Log.i(TAG, "event: initialized")
                replayBreakpointsOnInitialized()
            }
            "output" -> {
                val body = event.optJSONObject("body") ?: return
                val category = body.optString("category", "console")
                val text = body.optString("output", "")
                if (text.isNotEmpty()) {
                    if (category == "stderr" || category == "stdout") {
                        _output.tryEmit(text)
                    } else {
                        // console / telemetry / important — surface with a prefix
                        _output.tryEmit("[$category] $text")
                    }
                }
            }
            "stopped" -> handleStopped(event)
            "continued" -> {
                val body = event.optJSONObject("body")
                val pid = body?.optInt("threadId", currentThreadId) ?: currentThreadId
                _state.value = DebuggerState.Running(pid)
            }
            "thread" -> {
                // Track the first seen thread so step/continue have a target
                // even before the first `stopped` event.
                val body = event.optJSONObject("body") ?: return
                val tid = body.optInt("threadId", -1)
                if (tid > 0 && currentThreadId <= 0) {
                    currentThreadId = tid
                }
            }
            "terminated", "exited" -> {
                val body = event.optJSONObject("body")
                val code = body?.optInt("exitCode", 0) ?: 0
                Log.i(TAG, "event: $name exitCode=$code")
                _state.value = DebuggerState.Exited(code = code, signaled = false)
                sessionLock.withLock { shutdownSession() }
            }
            "breakpoint" -> {
                // DAP fires this when a breakpoint's verification state
                // changes after the fact (e.g. a .so loaded that contains
                // the target line). Update the affected entry.
                val body = event.optJSONObject("body") ?: return
                val bp = body.optJSONObject("breakpoint") ?: return
                val path = bp.optJSONObject("source")?.optString("path")
                val line = bp.optInt("line", -1)
                if (path != null && line > 0) {
                    val key = SourceBreakpoint(filePath = path, line = line)
                    val existing = _breakpoints.value[key] ?: return
                    val updated = existing.copy(
                        verified = bp.optBoolean("verified", existing.verified),
                        message = bp.optStringOrNull("message") ?: existing.message,
                    )
                    _breakpoints.value = _breakpoints.value + (key to updated)
                }
            }
            "process" -> {
                // Informational — lldb-dap emits this at launch.
            }
            "module" -> {
                // Library loaded/unloaded. Not surfaced to UI.
            }
            else -> {
                Log.v(TAG, "unhandled event: $name")
            }
        }
    }

    private suspend fun handleStopped(event: JSONObject) {
        val body = event.optJSONObject("body") ?: return
        val threadId = body.optInt("threadId", currentThreadId)
        if (threadId > 0) currentThreadId = threadId

        val reasonStr = body.optString("reason", "")
        val reason = when (reasonStr.lowercase()) {
            "breakpoint" -> StopReason.BREAKPOINT
            "step" -> StopReason.STEP
            "pause" -> StopReason.PAUSE
            "signal" -> StopReason.SIGNAL
            "exception" -> StopReason.EXCEPTION
            "entry" -> StopReason.ENTRY
            else -> StopReason.UNKNOWN
        }
        val description = body.optStringOrNull("description")
            ?: body.optStringOrNull("text")
            ?: reasonStr.ifEmpty { null }

        // Fetch a fresh stack trace for the stopped thread. Best-effort —
        // on failure we still publish a Stopped state with no stack.
        val stack = fetchStackTrace(threadId)
        val top = stack.firstOrNull()

        // Auto-continue past stops that aren't in user code.
        //
        // We have two failure modes to handle:
        //
        //  1. ENTRY phase (before reaching user_main_fn): the trampoline
        //     sets env, dlopens libuser-debug.so, raises SIGTRAP as a
        //     sync point, then calls run_user_main. Each of those can
        //     produce a stop (signals, module-load callbacks, instruction
        //     trap) that the user doesn't care about. We continue past
        //     all of them until the FIRST stop with reason=BREAKPOINT —
        //     which is what the pending bp on `user_main_fn` produces
        //     once libuser-debug.so loads.
        //
        //  2. EXIT phase (after returning from user_main_fn): control
        //     unwinds back through the runtime shim → trampoline →
        //     program exit. If the user steps off the last line of
        //     their main(), the next stop lands in the runtime shim
        //     or the trampoline — code the user doesn't care about
        //     and shouldn't have to manually step through.
        //
        // Unified heuristic: a stop is "user code" if its top-frame
        // source file is INSIDE the project root. Anything else
        // (system headers, libc, runtime shim, trampoline, loader)
        // is silently continued past.
        //
        // Capped at MAX_AUTO_CONTINUE_HOPS as a safety net.
        val topSrc = top?.sourceFile
        val isUserCode = topSrc != null
            && projectRootPrefix?.let { prefix ->
                runCatching { java.io.File(topSrc).canonicalFile.absolutePath }
                    .getOrNull()
                    ?.startsWith(prefix.trimEnd('/')) == true
            } == true

        if (isUserCode) {
            // First time we see user code: lock in `userCodeReached`
            // so we don't fall back into entry-phase pre-stop behavior
            // if a later stop briefly looks weird.
            userCodeReached = true
        } else if (autoContinueHops < MAX_AUTO_CONTINUE_HOPS) {
            // Always silently continue past non-user stops, regardless
            // of whether we're in entry phase or exit phase.
            autoContinueHops++
            Log.i(
                TAG,
                "auto-continue past non-user stop #$autoContinueHops: " +
                    "reason=$reason desc=$description frame=${top?.name} " +
                    "src=$topSrc",
            )
            val s = session ?: return
            try {
                s.conn.sendRequest(
                    command = "continue",
                    arguments = JSONObject().apply { put("threadId", threadId) },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "auto-continue failed", t)
            }
            return
        }

        _state.value = DebuggerState.Stopped(
            pid = 0,  // DAP doesn't give us the real pid
            reason = reason,
            threadId = threadId,
            sourceFile = top?.sourceFile,
            sourceLine = top?.sourceLine,
            callStack = stack,
            description = description,
        )
    }

    private suspend fun fetchStackTrace(threadId: Int): List<StackFrame> {
        val s = session ?: return emptyList()
        return try {
            val resp = s.conn.sendRequest(
                command = "stackTrace",
                arguments = JSONObject().apply {
                    put("threadId", threadId)
                    put("startFrame", 0)
                    put("levels", MAX_STACK_FRAMES)
                },
                timeoutMs = 10_000,
            )
            if (!resp.optBoolean("success")) return emptyList()
            val body = resp.optJSONObject("body") ?: return emptyList()
            val frames = body.optJSONArray("stackFrames") ?: return emptyList()
            buildList {
                for (i in 0 until frames.length()) {
                    val f = frames.optJSONObject(i) ?: continue
                    val src = f.optJSONObject("source")
                    add(
                        StackFrame(
                            id = f.optInt("id", -1),
                            name = f.optString("name", "?"),
                            sourceFile = src?.optStringOrNull("path")
                                ?: src?.optStringOrNull("name"),
                            sourceLine = if (f.has("line")) f.optInt("line") else null,
                            instructionPointer = f.optStringOrNull("instructionPointerReference"),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stackTrace failed", t)
            emptyList()
        }
    }

    // ---- helpers ----

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return v.ifEmpty { null }
    }

    companion object {
        private const val TAG = "lldb-dap"
        private const val MAX_STACK_FRAMES = 64

        /**
         * Cap on auto-continues per session. We auto-continue both on
         * the way IN (loader → trampoline → SIGTRAP, 3-5 hops) and on
         * the way OUT (runtime shim → trampoline → exit, 3-5 hops),
         * plus any incidental stops in STL headers when the user
         * steps through code that uses std:: types. 20 is comfortable
         * headroom; more than this almost certainly means we're stuck
         * in a non-user-code loop and should surface it.
         */
        private const val MAX_AUTO_CONTINUE_HOPS = 20
    }
}
