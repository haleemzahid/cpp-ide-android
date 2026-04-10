package dev.cppide.core.debug

import android.util.Log
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.toolchain.Toolchain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
                sock.soTimeout = 5000
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

    override suspend fun stepInstruction() = withContext(dispatchers.io) {
        checkStopped("stepInstruction")
        cmdMutex.withLock {
            _state.value = DebuggerState.Running(currentPid())
            writePacket("vCont;s")
            val reply = replyChannel!!.receive()
            sendAck()
            applyStopReply(reply)
        }
    }

    override suspend fun cont() = withContext(dispatchers.io) {
        checkStopped("cont")
        cmdMutex.withLock {
            _state.value = DebuggerState.Running(currentPid())
            writePacket("vCont;c")
            val reply = replyChannel!!.receive()
            sendAck()
            applyStopReply(reply)
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
        if (_state.value == DebuggerState.Idle) return@withContext
        Log.i(TAG, "stop() called")
        // Best-effort $k (kill). Don't wait — we're about to close
        // everything anyway.
        runCatching {
            synchronized(output2 ?: return@runCatching) {
                output2!!.write("\$k#6b".toByteArray(Charsets.US_ASCII))
                output2!!.flush()
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
                val reason = when (reasonStr) {
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
                Log.i(TAG, "stopped reason=$reasonStr sig=$sig pc=0x${pc.toString(16)} tid=$tid")
                _state.value = DebuggerState.Stopped(
                    pid = currentPid(),
                    pc = pc,
                    reason = reason,
                    signal = sig,
                    threadId = tid,
                )
            }
            else -> Log.w(TAG, "applyStopReply: unrecognised '${body.take(20)}'")
        }
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
    }

    companion object {
        private const val TAG = "cppide-debugger"
    }
}
