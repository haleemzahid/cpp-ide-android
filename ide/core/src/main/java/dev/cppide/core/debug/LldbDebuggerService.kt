package dev.cppide.core.debug

import android.util.Log
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.toolchain.Toolchain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Production [DebuggerService] backed by lldb-server gdbserver + our
 * handwritten GDB-remote serial protocol client. No lldb liblldb.so,
 * no lldb-dap — just lldb-server and a socket.
 *
 * ## Architecture
 *
 *     start() ──► spawn lldb-server gdbserver 127.0.0.1:P -- trampoline user.so
 *                 │
 *                 ▼
 *               handshake (+/QStartNoAckMode/qSupported/QListThreadsInStopReply/?)
 *                 │
 *                 ▼
 *               reader coroutine starts, owns the socket read side
 *                 │
 *                 ▼  parses every incoming packet, routes:
 *                 │   `O<hex>` → decoded → [output] SharedFlow
 *                 │   anything else → replyChannel
 *                 │
 *     cont() / step() / pause() / stop() submit through [cmdMutex]:
 *       write packet → wait replyChannel → parse → update [state]
 *
 * `pause()` is the only method that writes bytes without the mutex —
 * it sends a raw 0x03 "interrupt" byte, which races with the in-flight
 * cont/step's read and causes lldb-server to stop the target and emit a
 * stop packet that the waiting cont/step picks up normally.
 *
 * ## What's NOT here (Phase 2 / Phase 3)
 *
 *  - No DWARF parsing or source-line mapping. PC is reported as a raw
 *    address; the UI can log it but not highlight it.
 *  - No `Z0/z0` breakpoint support yet. The API will gain
 *    setBreakpoint/clearBreakpoint in Phase 2.
 *  - No variable inspection, no stack unwinding, no target.xml parsing
 *    (we assume arm64 register-index 0x20 = PC, confirmed by the spike
 *    and by RegisterInfoPOSIX_arm64.cpp).
 */
class LldbDebuggerService(
    private val toolchain: Toolchain,
    private val dispatchers: DispatcherProvider,
) : DebuggerService {

    private val _state = MutableStateFlow<DebuggerState>(DebuggerState.Idle)
    override val state: StateFlow<DebuggerState> = _state.asStateFlow()

    private val _breakpoints = MutableStateFlow<Map<SourceBreakpoint, BreakpointState>>(emptyMap())
    override val breakpoints: StateFlow<Map<SourceBreakpoint, BreakpointState>> =
        _breakpoints.asStateFlow()

    /** Parsed debug info for the current session's binary. null until the
     *  user starts a debug session that loads it. */
    private var sourceMap: SourceMap? = null

    /** Load address of the user .so in the inferior. Discovered from
     *  qXfer:libraries-svr4:read after the initial stop. Breakpoint
     *  addresses are `dwarfAddress + userLibraryBase`. */
    private var userLibraryBase: Long = 0L

    /** Absolute path the current debug session loaded as the user binary.
     *  Used to match the right svr4 library entry. */
    private var currentUserLibraryPath: String? = null

    private val _output = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    override val output: SharedFlow<String> = _output.asSharedFlow()

    /** Scope for the reader coroutine + stderr drain. Cancelled on [stop]. */
    private var sessionScope: CoroutineScope? = null

    /** The lldb-server subprocess. */
    private var process: Process? = null

    /** TCP socket to lldb-server's gdbserver listener. */
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output2: OutputStream? = null

    /**
     * Rolling tail of lldb-server's stderr. Surfaced verbatim in
     * [DebuggerState.Failed] so silent failures (address-in-use, bad
     * target, missing library) aren't invisible to the user.
     */
    private val stderrTail = StringBuilder()
    private fun appendStderr(line: String) {
        synchronized(stderrTail) {
            stderrTail.append(line).append('\n')
            if (stderrTail.length > 4000) {
                stderrTail.delete(0, stderrTail.length - 4000)
            }
        }
    }
    private fun stderrSnapshot(): String = synchronized(stderrTail) { stderrTail.toString() }

    /**
     * Reply packets consumed by commands. The reader coroutine writes
     * every non-`O` packet here; command methods read one item per
     * command. Bounded to 16 — if the server ever sends two replies
     * before we read the first (shouldn't happen under the default RSP
     * state machine), we'd block rather than lose data.
     */
    private var replyChannel: Channel<String>? = null

    /** Serialises command issue so two coroutines can't interleave sends. */
    private val cmdMutex = Mutex()

    // ---- public API ------------------------------------------------------

    override suspend fun start(trampolineBinary: File, userLibrary: File): Result<Unit> =
        withContext(dispatchers.io) {
            if (_state.value.isActive) {
                return@withContext Result.failure(
                    IllegalStateException("debugger already running (state=${_state.value})")
                )
            }
            runCatching {
                _state.value = DebuggerState.Starting("resolving toolchain")
                synchronized(stderrTail) { stderrTail.clear() }
                val paths = toolchain.paths
                    ?: error("toolchain not installed — run install() first")

                val lldbServer = paths.lldbServer
                check(lldbServer.exists()) {
                    "lldb-server symlink missing at ${lldbServer.absolutePath}"
                }
                check(trampolineBinary.exists()) {
                    "trampoline binary missing at ${trampolineBinary.absolutePath}"
                }
                check(userLibrary.exists()) {
                    "user library missing at ${userLibrary.absolutePath}"
                }
                currentUserLibraryPath = userLibrary.absolutePath

                // Parse DWARF line info up-front so breakpoints can be
                // resolved the moment we know the load address.
                sourceMap = SourceMap.fromElf(userLibrary)
                Log.i(TAG, "sourceMap rows=${sourceMap?.rowCount ?: 0}")

                // Reap any orphaned lldb-server from a previous session
                // before we try to bind a new listen port. Without this,
                // a stale process from a crashed run holds our port and
                // the new launch fails silently with "address in use".
                reapOrphans()

                // 1. Spawn lldb-server. Pick a fresh TCP port so the spike
                // and the real debugger can coexist — the spike uses 5040,
                // so we take a different ephemeral one each time.
                _state.value = DebuggerState.Starting("launching lldb-server")
                val port = pickFreePort()
                val cmd = listOf(
                    lldbServer.absolutePath,
                    "gdbserver",
                    "127.0.0.1:$port",
                    "--",
                    trampolineBinary.absolutePath,
                    userLibrary.absolutePath,
                )
                Log.i(TAG, "spawn: $cmd")

                val workingDir = paths.termuxRoot
                val pb = ProcessBuilder(cmd)
                    .directory(workingDir)
                    .redirectErrorStream(false)
                pb.environment().putAll(paths.processEnv(workingDir = workingDir))
                val proc = pb.start()
                process = proc

                // Drain stderr into (a) logcat and (b) our rolling tail
                // so failures surface in DebuggerState.Failed instead of
                // disappearing behind a lone "Channel was closed".
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                sessionScope = scope
                scope.launch {
                    try {
                        proc.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach {
                                Log.w(TAG, "[lldb-server] $it")
                                appendStderr(it)
                            }
                        }
                    } catch (_: Throwable) { /* closed on stop */ }
                }

                // 2. Connect TCP. lldb-server doesn't print a ready line,
                // so we poll connect(2).
                _state.value = DebuggerState.Starting("connecting")
                val sock = connectWithRetry("127.0.0.1", port, timeoutMs = 5000)
                    ?: error("could not connect to 127.0.0.1:$port within 5s")
                sock.tcpNoDelay = true
                // NOTE: soTimeout intentionally NOT set. The reader coroutine
                // blocks on stream.read() indefinitely while the target is
                // stopped and the user is thinking — a finite timeout here
                // caused the reader to die after ~5s of idle, closing the
                // reply channel and turning every subsequent command into a
                // "Channel was closed" error.
                socket = sock
                input = sock.getInputStream()
                output2 = sock.getOutputStream()
                Log.i(TAG, "connected tcp://127.0.0.1:$port")

                // 3. Reader coroutine. After this, all reads happen there.
                val channel = Channel<String>(capacity = 16)
                replyChannel = channel
                scope.launch { readerLoop(input!!, channel) }

                // 4. Handshake. We hold the mutex so no command can race
                // with it — though nothing else should be running yet.
                //
                // Deliberately NOT using QStartNoAckMode. Experimentally,
                // lldb-server gdbserver on Android disconnects silently
                // when the very next packet after `QStartNoAckMode OK` is
                // `qSupported`, even though the checksum is accepted. The
                // spike probe (which skips QStartNoAckMode and sends `+`
                // manually after each reply) works cleanly, so we mirror
                // that pattern here. The cost is ~2 extra bytes per
                // exchange, which is irrelevant over localhost.
                cmdMutex.withLock {
                    // 4a. qSupported. We pair up writePacket + channel.receive
                    // followed by a manual `+` ack, copied from the working
                    // spike's exchange() helper.
                    _state.value = DebuggerState.Starting("capabilities")
                    Log.i(TAG, "handshake: qSupported")
                    writePacket("qSupported")
                    val caps = channel.receive()
                    sendAck()
                    Log.i(TAG, "caps: ${caps.take(200)}${if (caps.length > 200) "…" else ""}")

                    // 4b. Ask the server to include thread info inside
                    // every stop reply — avoids an extra qfThreadInfo
                    // round-trip after each stop.
                    writePacket("QListThreadsInStopReply")
                    val tir = channel.receive()
                    sendAck()
                    if (tir != "OK") Log.w(TAG, "QListThreadsInStopReply: $tir")

                    // 4c. Ask for the initial stop reason. lldb-server
                    // should already have the target stopped at entry
                    // (between execve and first user instruction).
                    _state.value = DebuggerState.Starting("initial stop")
                    writePacket("?")
                    val initial = channel.receive()
                    sendAck()
                    applyStopReply(initial)

                    // 4d. Fetch the loaded-library list so we know where
                    // libuser-debug.so was mapped. Required for
                    // breakpoint address resolution (DWARF addr + base).
                    //
                    // BUT — at this point the target is stopped inside
                    // ld-android.so BEFORE it has dlopen'd our user .so.
                    // The library list will only contain the trampoline
                    // itself. That's fine for Phase 2: we re-fetch the
                    // library list after each stop to catch the user .so
                    // when it appears (usually right after the trampoline
                    // reaches its dlopen call).
                    refreshLibraryList()
                    applyPendingBreakpoints()
                }

                Result.success(Unit)
            }.onFailure { t ->
                Log.e(TAG, "start failed", t)
                // Wait briefly so the stderr drain can flush any final
                // error lines from lldb-server before we snapshot.
                try { kotlinx.coroutines.delay(100) } catch (_: Throwable) {}
                val tail = stderrSnapshot().trim()
                val message = buildString {
                    append("${t.javaClass.simpleName}: ${t.message}")
                    if (tail.isNotEmpty()) {
                        append("\nlldb-server stderr:\n")
                        append(tail)
                    }
                }
                _state.value = DebuggerState.Failed(message)
                cleanup()
            }.getOrThrow()
        }

    override suspend fun toggleBreakpoint(file: String, line: Int) = withContext(dispatchers.io) {
        val basename = file.substringAfterLast('/').substringAfterLast('\\')
        val key = SourceBreakpoint(basename, line)
        val current = _breakpoints.value
        if (key in current) {
            // Remove. If live, send $z1.
            val existing = current[key]!!
            if (state.value.isActive && existing.runtimeAddress != null) {
                runCatching {
                    cmdMutex.withLock {
                        writePacket("z1,${existing.runtimeAddress.toString(16)},4")
                        val reply = replyChannel?.receive() ?: return@withLock
                        sendAck()
                        if (reply != "OK") Log.w(TAG, "z1 rejected: $reply")
                    }
                }
            }
            _breakpoints.value = current - key
        } else {
            val entry = BreakpointState(key)
            _breakpoints.value = current + (key to entry)
            // If a session is live AND we know the load base, resolve now.
            if (state.value.isActive && userLibraryBase != 0L) {
                applySingleBreakpoint(key)
            }
        }
    }

    override suspend fun clearBreakpoints() = withContext(dispatchers.io) {
        val live = _breakpoints.value.values.filter { it.runtimeAddress != null }
        if (state.value.isActive && live.isNotEmpty()) {
            runCatching {
                cmdMutex.withLock {
                    for (bp in live) {
                        writePacket("z1,${bp.runtimeAddress!!.toString(16)},4")
                        replyChannel?.receive()
                        sendAck()
                    }
                }
            }
        }
        _breakpoints.value = emptyMap()
    }

    override suspend fun stepInstruction() = withContext(dispatchers.io) {
        checkStopped("stepInstruction")
        runResumeCommand(resumePacket = "vCont;s", thenContinue = false)
    }

    override suspend fun cont() = withContext(dispatchers.io) {
        checkStopped("cont")
        runResumeCommand(resumePacket = "vCont;c", thenContinue = false)
    }

    /**
     * Resume execution. With hardware breakpoints (Z1) there is NO need
     * for a lift/step/re-insert dance — HW breakpoints live in arm64
     * debug registers, not in target memory, so the instruction at the
     * breakpoint PC is pristine and a plain `vCont;c` will re-execute
     * it and the breakpoint will fire again on the next iteration.
     * That's exactly what a for-loop breakpoint needs.
     *
     * We used to do the software-breakpoint dance here (z0/vCont;s/Z0),
     * but the Z0 re-insert reliably hung lldb-server for tens of
     * seconds after a trace stop — a known class of RSP framing issue
     * on the client side. Switching to Z1 sidesteps it entirely and
     * matches what LLVM's own research (up to 16 HW breakpoint slots
     * exposed via PTRACE_SETREGSET + NT_ARM_HW_BREAK) says should work.
     */
    private suspend fun runResumeCommand(resumePacket: String, thenContinue: Boolean) {
        try {
            cmdMutex.withLock {
                _state.value = DebuggerState.Running(currentPid())
                writePacket(resumePacket)
                val reply = replyChannel?.receiveSafe() ?: return@withLock
                sendAck()
                applyStopReply(reply)
            }
        } catch (_: ClosedReceiveChannelException) {
            // Session went away under us (stop() or onCleared). Not an
            // error; cleanup has already happened or is in flight.
            Log.i(TAG, "resume command: channel closed, session ended")
        }
        onStoppedAfterCommand()
    }

    /** Non-throwing channel receive. Returns null if the channel is
     *  closed (cleanup raced with us) so we bail out gracefully. */
    private suspend fun Channel<String>?.receiveSafe(): String? {
        if (this == null) return null
        return try { receive() } catch (_: ClosedReceiveChannelException) { null }
    }

    /**
     * Hook run after cont/step returns. If the new state is Stopped, we:
     *  1. Refresh the svr4 library list so any newly-loaded .so (e.g. the
     *     user's lib after the trampoline dlopens it) becomes visible.
     *  2. Retry resolution of any unverified breakpoints.
     *
     * This is what lets a breakpoint set before `start()` actually fire —
     * it gets applied when we hit the trampoline's SIGTRAP sync point.
     */
    private suspend fun onStoppedAfterCommand() {
        if (_state.value !is DebuggerState.Stopped) return
        if (_breakpoints.value.none { !it.value.verified }) return
        // Takes cmdMutex internally via the write sequence.
        try {
            cmdMutex.withLock {
                refreshLibraryList()
            }
            applyPendingBreakpoints()
        } catch (t: Throwable) {
            Log.w(TAG, "onStoppedAfterCommand failed", t)
        }
    }

    override suspend fun pause() = withContext(dispatchers.io) {
        // Deliberately do NOT take the mutex — the target is running and
        // a cont/step is blocked on the replyChannel. We write the raw
        // interrupt byte directly, lldb-server stops the target, sends a
        // stop packet, and the blocked coroutine picks it up.
        val out = output2 ?: run {
            Log.w(TAG, "pause(): not connected")
            return@withContext
        }
        try {
            Log.i(TAG, "pause(): sending interrupt byte")
            synchronized(out) {
                out.write(0x03)
                out.flush()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pause() failed", t)
        }
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        val current = _state.value
        if (current == DebuggerState.Idle ||
            current is DebuggerState.Exited ||
            current is DebuggerState.Failed) {
            // Nothing to kill — but still run cleanup to free any dangling
            // socket/process handles that the exit path may have missed.
            cleanup()
            if (current !is DebuggerState.Failed) {
                _state.value = DebuggerState.Idle
            }
            return@withContext
        }
        Log.i(TAG, "stop() called in state=$current")
        // Best-effort $k (kill). Don't wait — we're about to close
        // everything anyway.
        val out = output2
        if (out != null) {
            runCatching {
                synchronized(out) {
                    out.write("\$k#6b".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
            }
        }
        cleanup()
        _state.value = DebuggerState.Idle
    }

    // ---- reader coroutine ------------------------------------------------

    /**
     * Consume the input stream, framing and routing every GDB-remote
     * packet. Runs on an IO dispatcher in [sessionScope]. Exits when
     * the stream closes (process death or deliberate [stop]).
     */
    private suspend fun readerLoop(
        stream: InputStream,
        channel: Channel<String>,
    ) {
        val sb = StringBuilder()
        val buf = ByteArray(8192)
        try {
            while (true) {
                Log.i(TAG, "reader: blocking on read…")
                val n = stream.read(buf)
                if (n <= 0) {
                    Log.i(TAG, "reader: EOF (n=$n)")
                    break
                }
                // RAW WIRE TRACE — remove once the protocol is stable.
                Log.i(TAG, "reader: <- ${n}B raw=${
                    String(buf, 0, n.coerceAtMost(200), Charsets.US_ASCII)
                        .replace("\n", "\\n")
                }")
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                // Frame every `$...#cc` in the buffer. Drop `+`/`-`
                // acks (harmless in no-ack mode but lldb-server may
                // send one during the first handshake before no-ack
                // takes effect).
                while (true) {
                    val dollar = sb.indexOf('$')
                    if (dollar < 0) {
                        // No packet start — discard any stray bytes.
                        if (sb.isNotEmpty() &&
                            (sb[0] == '+' || sb[0] == '-')) {
                            sb.deleteCharAt(0)
                            continue
                        }
                        sb.clear()
                        break
                    }
                    val hash = sb.indexOf('#', startIndex = dollar)
                    if (hash < 0 || hash + 2 >= sb.length) break  // incomplete
                    val body = sb.substring(dollar + 1, hash)
                    // Consume the packet body + the 2 checksum chars.
                    sb.delete(0, hash + 3)
                    routePacket(body, channel)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reader loop ended: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            channel.close()
            Log.i(TAG, "reader: exiting")
        }
    }

    private suspend fun routePacket(body: String, channel: Channel<String>) {
        when {
            body.isEmpty() -> { /* empty ACK, drop */ }
            body.startsWith("O") && body.length > 1 && body[1] != 'K' -> {
                // Inferior stdout. Payload is lowercase hex ASCII.
                val decoded = decodeHexAscii(body.substring(1))
                if (decoded.isNotEmpty()) _output.emit(decoded)
            }
            else -> {
                // Reply or async stop packet — command waiters consume it.
                channel.send(body)
            }
        }
    }

    // ---- protocol helpers ------------------------------------------------

    /**
     * Manual `+` ack for the last packet the server sent us. In default
     * RSP mode (no QStartNoAckMode negotiated) the client must ack every
     * reply the server sends, or the server will close the session after
     * the second exchange. We call this from every exchange flow.
     */
    private fun sendAck() {
        val out = output2 ?: return
        try {
            synchronized(out) {
                out.write('+'.code)
                out.flush()
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    /** Send a GDB-remote packet with correct checksum. Does not wait for a reply. */
    private fun writePacket(body: String) {
        val out = output2 ?: error("not connected")
        val cc = body.sumOf { it.code } and 0xff
        val pkt = "\$$body#${"%02x".format(cc)}"
        Log.i(TAG, "-> $pkt")
        synchronized(out) {
            out.write(pkt.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    /**
     * Parse a stop reply ($T.., $S.., $W.., $X..) and update [_state].
     * The format is:
     *
     *   T<sig>{key:value;}*     — thread stopped, key/value extras
     *     keys include: thread, name, reason, <reg_index>, watch/rwatch/awatch
     *   S<sig>                   — simple stop (legacy, rare)
     *   W<status>                — process exited with status
     *   X<signal>                — process killed by signal
     */
    private fun applyStopReply(body: String) {
        if (body.isEmpty()) {
            Log.w(TAG, "applyStopReply: empty body")
            return
        }
        when (body[0]) {
            'W' -> {
                val code = body.drop(1).takeWhile { it != ';' }.toIntOrNull(16) ?: 0
                Log.i(TAG, "target exited W $code")
                _state.value = DebuggerState.Exited(code, signaled = false)
                cleanup()
            }
            'X' -> {
                val sig = body.drop(1).takeWhile { it != ';' }.toIntOrNull(16) ?: 0
                Log.i(TAG, "target killed X $sig")
                _state.value = DebuggerState.Exited(sig, signaled = true)
                cleanup()
            }
            'S' -> {
                val sig = body.drop(1).take(2).toIntOrNull(16) ?: 0
                _state.value = DebuggerState.Stopped(
                    pid = currentPid(),
                    pc = 0,
                    reason = StopReason.SIGNAL,
                    signal = sig,
                    threadId = "",
                )
            }
            'T' -> {
                val sig = body.substring(1, 3).toIntOrNull(16) ?: 0
                val kv = parseKeyValues(body.substring(3))
                val reasonStr = kv["reason"] ?: ""
                var reason = when (reasonStr) {
                    "breakpoint" -> StopReason.BREAKPOINT
                    "trace" -> StopReason.TRACE
                    "trap" -> StopReason.TRAP
                    "signal" -> StopReason.SIGNAL
                    "watchpoint" -> StopReason.WATCHPOINT
                    "exception" -> StopReason.EXCEPTION
                    else -> StopReason.UNKNOWN
                }
                // PC at arm64 register index 0x20. Parsed as little-endian hex.
                val pc = kv["20"]?.let { parseHexLE(it) } ?: 0L
                val tid = kv["thread"] ?: ""

                // lldb-server reports "signal" for our Z0 breakpoint hits
                // (sig=5 = SIGTRAP), not "breakpoint", because the swbreak
                // stop-reason capability wasn't negotiated. Detect by PC
                // matching any of our set breakpoints and rewrite the
                // reason so cont/step know to do the step-over dance and
                // the UI shows "breakpoint" instead of "signal".
                if (reason == StopReason.SIGNAL && sig == 5) {
                    if (_breakpoints.value.values.any {
                            it.runtimeAddress != null && it.runtimeAddress == pc
                        }) {
                        reason = StopReason.BREAKPOINT
                    }
                }

                // Best-effort source lookup: PC → (file, line) via DWARF.
                // The PC in the T packet is a RUNTIME address; we need to
                // subtract userLibraryBase to get a DWARF-relative address
                // before querying the map.
                val (srcFile, srcLine) = resolvePcToSource(pc)

                Log.i(TAG, "stopped reason=$reasonStr sig=$sig pc=0x${pc.toString(16)} " +
                    "tid=$tid src=${srcFile?.let { "$it:$srcLine" } ?: "(unknown)"}")
                _state.value = DebuggerState.Stopped(
                    pid = currentPid(),
                    pc = pc,
                    reason = reason,
                    signal = sig,
                    threadId = tid,
                    sourceFile = srcFile,
                    sourceLine = srcLine,
                )
            }
            else -> Log.w(TAG, "applyStopReply: unrecognised '${body.take(20)}'")
        }
    }

    /**
     * Translate a runtime PC to a source file + line via the DWARF line
     * map for the user library. Returns `(null, null)` if:
     *  - sourceMap hasn't been built yet (pre-handshake)
     *  - userLibraryBase isn't known (pre-dlopen)
     *  - PC is outside the user .so's address range
     *  - DWARF has no row for that address (PC is in libc, ld, etc.)
     */
    private fun resolvePcToSource(pc: Long): Pair<String?, Int?> {
        val map = sourceMap ?: return null to null
        val base = userLibraryBase
        if (base == 0L) return null to null
        // Subtract load base so we query with DWARF-relative addresses.
        val dwarfAddr = pc - base
        if (dwarfAddr < 0) return null to null
        val loc = map.findLocation(dwarfAddr) ?: return null to null
        return loc.file to loc.line
    }

    /** Parse a `key:value;key:value;` tail from a T packet. */
    private fun parseKeyValues(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < s.length) {
            val colon = s.indexOf(':', i)
            if (colon < 0) break
            val semi = s.indexOf(';', colon + 1).let { if (it < 0) s.length else it }
            val key = s.substring(i, colon)
            val value = s.substring(colon + 1, semi)
            out[key] = value
            i = semi + 1
        }
        return out
    }

    /** Parse a little-endian hex string into a Long (e.g. "90e6ffff7f000000" → 0x7fffffe690). */
    private fun parseHexLE(hex: String): Long {
        var result = 0L
        var i = 0
        var shift = 0
        while (i + 1 < hex.length && shift < 64) {
            val b = hex.substring(i, i + 2).toLongOrNull(16) ?: return 0L
            result = result or (b shl shift)
            i += 2
            shift += 8
        }
        return result
    }

    /** Decode a hex-ASCII stream (`6865 6c6c 6f`) into the original bytes. */
    private fun decodeHexAscii(hex: String): String {
        if (hex.length % 2 != 0) return ""
        val bytes = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: return ""
            bytes[i / 2] = b.toByte()
            i += 2
        }
        return String(bytes, Charsets.UTF_8)
    }

    // `Process.pid()` exists on API 26+ but isn't visible in the Kotlin
    // stubs we compile against for :core. We don't actually need our
    // subprocess PID for anything load-bearing (the target PID comes
    // from the stop packet's thread:xx key); return -1 as a placeholder.
    private fun currentPid(): Int = -1

    private fun checkStopped(op: String) {
        val s = _state.value
        check(s is DebuggerState.Stopped) {
            "$op requires Stopped state, current=$s"
        }
    }

    private fun connectWithRetry(host: String, port: Int, timeoutMs: Long): Socket? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 200)
                return s
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        return null
    }

    /**
     * Ask the kernel for a currently-unused TCP port by binding to 0.
     * Immediately close the socket and return the assigned port — there
     * is a narrow race window before lldb-server rebinds, but in
     * practice nothing else on the device is after that port, and we're
     * running everything on localhost.
     */
    private fun pickFreePort(): Int {
        java.net.ServerSocket(0).use { return it.localPort }
    }

    /**
     * Walk /proc and SIGKILL any lldb-server-as-libLLDBServer.so process
     * owned by this UID that isn't our current [process]. A previous
     * crashed session can leave gdbserver orphans holding stdin pipes
     * and TCP ports; reaping them at start keeps launches reliable.
     * Best-effort; failure is non-fatal.
     */
    private fun reapOrphans() {
        val killed = mutableListOf<Int>()
        try {
            val myPid = android.os.Process.myPid()
            val procRoot = File("/proc")
            val entries = procRoot.listFiles() ?: return
            for (e in entries) {
                val name = e.name
                if (!name.all { it.isDigit() }) continue
                val pid = name.toInt()
                if (pid == myPid) continue
                val cmdline = runCatching {
                    File(e, "cmdline").readText()
                }.getOrNull() ?: continue
                // cmdline entries are NUL-separated; argv[0] is before the first NUL.
                val argv0 = cmdline.substringBefore('\u0000')
                if (argv0.contains("libLLDBServer.so")) {
                    runCatching { android.os.Process.killProcess(pid) }
                        .onSuccess { killed += pid }
                    Log.i(TAG, "reapOrphans: killed stale lldb-server pid=$pid")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reapOrphans failed (non-fatal): ${t.message}")
        }
        if (killed.isNotEmpty()) Thread.sleep(100)  // let ports free
    }

    /**
     * Fetch `qXfer:libraries-svr4:read:` in chunks and extract the
     * runtime load address of [currentUserLibraryPath]. Must be called
     * with [cmdMutex] held (we already are inside the handshake block).
     *
     * The response is XML that looks like:
     *
     *   <library-list-svr4 version="1.0" main-lm="0x...">
     *     <library name="/path/to/libfoo.so" lm="0x..." l_addr="0x1234"
     *              l_ld="0x..."/>
     *     ...
     *   </library-list-svr4>
     *
     * We read until we see `l`-prefixed last-chunk marker and regex-extract
     * the entry whose name matches. Crude by design — this is a tight
     * Phase 2 MVP; if it ever matters we can replace with a real XML
     * pull parser.
     */
    private suspend fun refreshLibraryList() {
        val channel = replyChannel ?: return
        val target = currentUserLibraryPath ?: return
        val xml = StringBuilder()
        var offset = 0
        val chunkSize = 0x1000
        while (true) {
            writePacket("qXfer:libraries-svr4:read::${offset.toString(16)},${chunkSize.toString(16)}")
            val reply = channel.receive()
            sendAck()
            if (reply.isEmpty()) break
            val marker = reply[0]
            val body = reply.substring(1)
            xml.append(body)
            if (marker == 'l') break      // last chunk
            if (marker != 'm') break       // unexpected
            offset += body.length
            if (body.isEmpty()) break
        }
        val doc = xml.toString()
        Log.i(TAG, "libraries-svr4: ${doc.length} chars")

        // Find <library ...> entries whose `name=` attribute matches our
        // user library (full path or basename).
        val targetBasename = target.substringAfterLast('/')
        val libTagRegex = Regex("""<library\s+[^>]*>""")
        val nameRegex = Regex("""name="([^"]+)"""")
        val lAddrRegex = Regex("""l_addr="(0x[0-9a-fA-F]+|[0-9]+)"""")

        for (match in libTagRegex.findAll(doc)) {
            val tag = match.value
            val name = nameRegex.find(tag)?.groupValues?.get(1) ?: continue
            val nameBasename = name.substringAfterLast('/')
            if (name == target || nameBasename == targetBasename) {
                val raw = lAddrRegex.find(tag)?.groupValues?.get(1) ?: continue
                userLibraryBase = if (raw.startsWith("0x") || raw.startsWith("0X")) {
                    raw.substring(2).toLong(16)
                } else {
                    raw.toLong()
                }
                Log.i(TAG, "userLibraryBase=0x${userLibraryBase.toString(16)} ($name)")
                return
            }
        }
        Log.w(TAG, "user library $targetBasename not in svr4 list yet")
    }

    /** Iterate pending (unresolved) breakpoints and try to set them. */
    private suspend fun applyPendingBreakpoints() {
        val map = _breakpoints.value
        if (map.isEmpty()) return
        for (entry in map.values) {
            if (!entry.verified) applySingleBreakpoint(entry.source)
        }
    }

    /**
     * Resolve one source breakpoint to a runtime address and send
     * `$Z0,addr,4`. Updates state flow with the verified address on
     * success, or leaves it unverified on failure.
     *
     * Expects the caller to NOT hold [cmdMutex] — this takes it itself.
     */
    private suspend fun applySingleBreakpoint(key: SourceBreakpoint) {
        val map = sourceMap ?: run {
            Log.w(TAG, "applyBreakpoint: no sourceMap")
            return
        }
        val base = userLibraryBase
        if (base == 0L) {
            Log.w(TAG, "applyBreakpoint: userLibraryBase unknown yet")
            return
        }
        val dwarfAddr = map.findAddress(key.fileBasename, key.line) ?: run {
            Log.w(TAG, "applyBreakpoint: ${key.fileBasename}:${key.line} not in debug info")
            return
        }
        val runtimeAddr = dwarfAddr + base
        Log.i(TAG, "applyBreakpoint: ${key.fileBasename}:${key.line} " +
            "dwarf=0x${dwarfAddr.toString(16)} runtime=0x${runtimeAddr.toString(16)}")

        val channel = replyChannel ?: return
        try {
            cmdMutex.withLock {
                // Z1 = hardware breakpoint. arm64's up to 16 BVR/BCR
                // slots are plenty for hand-set source breakpoints and
                // the HW path needs no lift/step/re-insert dance on hit.
                writePacket("Z1,${runtimeAddr.toString(16)},4")
                val reply = channel.receive()
                sendAck()
                if (reply == "OK") {
                    _breakpoints.update { cur ->
                        val existing = cur[key] ?: return@update cur
                        cur + (key to existing.copy(
                            runtimeAddress = runtimeAddr,
                            verified = true,
                        ))
                    }
                    Log.i(TAG, "breakpoint set: ${key.fileBasename}:${key.line}")
                } else {
                    Log.w(TAG, "Z1 rejected: $reply")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyBreakpoint write failed", t)
        }
    }

    /**
     * Idempotent teardown. Safe to call multiple times and from either
     * the reader thread (on target exit) or the stop() path (on user
     * action). Everything is nulled out, sockets closed, subprocess
     * killed, coroutine scope cancelled. Breakpoints ARE preserved —
     * the user's red dots should survive a stop/start cycle.
     */
    @Synchronized
    private fun cleanup() {
        try { socket?.close() } catch (_: Throwable) {}
        try { process?.destroyForcibly() } catch (_: Throwable) {}
        socket = null
        input = null
        output2 = null
        process = null
        replyChannel?.close()
        replyChannel = null
        sessionScope?.let { scope ->
            scope.coroutineContext[Job]?.cancel()
        }
        sessionScope = null
        sourceMap = null
        userLibraryBase = 0L
        currentUserLibraryPath = null
        // Mark user breakpoints as unverified so next session re-resolves
        // them against fresh load addresses. Don't clear them — that's the
        // user's data, they should survive session boundaries.
        _breakpoints.update { cur ->
            cur.mapValues { (_, bp) -> bp.copy(runtimeAddress = null, verified = false) }
        }
    }

    companion object {
        private const val TAG = "cppide-debugger"
    }
}
