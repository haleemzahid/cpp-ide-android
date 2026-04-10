package dev.cppide.core.debug

import android.util.Log
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.toolchain.Toolchain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Throwaway spike whose only job is to answer: "Can we launch lldb-server
 * on Android 15 under untrusted_app_35, open a TCP socket to it, and get a
 * `qSupported` response over the GDB-remote serial protocol?"
 *
 * If yes → a real step debugger is architecturally viable. If no → we
 * fall back to cooperative instrumentation or a forked-child architecture.
 *
 * This class builds nothing reusable. Every concern — process spawning,
 * socket IO, GDB packet framing — is inlined here so the spike is legible
 * at a glance. Delete this file once we commit to a debugger design.
 *
 * ## What it does
 * 1. Spawns `lldb-server platform --listen 127.0.0.1:<port> --server`
 *    using the same process-env machinery as clangd (PATH +
 *    LD_LIBRARY_PATH pointing at nativeLibraryDir).
 * 2. Drains stderr into logcat on a side coroutine.
 * 3. Polls TCP on the port for up to ~5 s.
 * 4. Sends a single GDB-remote packet: `$qSupported#37` (checksum 0x37).
 * 5. Reads the reply, looks for `PacketSize=`, reports success with the
 *    banner line. Any other failure mode returns a [Result.Failed] with
 *    the exact stage we died at.
 *
 * ## What it intentionally does not do
 * - No real debugee, no `attach`, no breakpoints, no registers.
 * - No full RSP parsing — we just want one packet round-trip.
 * - No lifecycle management — the lldb-server process leaks after this
 *   returns (by design; spike code, `destroy` in finally).
 */
class DebuggerSpike(
    private val toolchain: Toolchain,
    private val dispatchers: DispatcherProvider,
) {

    /** Result envelope. `Ok.banner` is the raw string lldb-server sent back. */
    sealed class Result {
        data class Ok(val banner: String, val stderrTail: String) : Result()
        data class Failed(
            val stage: String,
            val message: String,
            val stderrTail: String,
        ) : Result()
    }

    /**
     * Platform-mode reachability probe. Launches lldb-server in `platform`
     * mode (no target), confirms TCP + qSupported round-trip. This proves
     * the transport works but does NOT exercise ptrace.
     */
    suspend fun runPlatform(): Result = withContext(dispatchers.io) {
        Log.i(TAG, "runPlatform(): starting")
        val paths = toolchain.paths
            ?: return@withContext Result.Failed(
                stage = "toolchain",
                message = "toolchain not installed",
                stderrTail = "",
            )

        val lldbServer = paths.lldbServer
        if (!lldbServer.exists()) {
            return@withContext Result.Failed(
                stage = "resolve",
                message = "lldb-server symlink not found at ${lldbServer.absolutePath}",
                stderrTail = "",
            )
        }
        Log.i(TAG, "lldb-server: ${lldbServer.absolutePath}")

        // 1. Pick a port. Don't probe — if it's in use, lldb-server will
        //    fail fast and tell us in stderr.
        val port = 5039

        // 2. Spawn lldb-server in platform mode. `platform --server` starts
        //    a listener that accepts a platform protocol handshake, but
        //    it also speaks enough of the gdb-remote packet layer for our
        //    `qSupported` round-trip to come back with a usable payload.
        val workingDir = paths.termuxRoot
        val cmd = listOf(
            lldbServer.absolutePath,
            "platform",
            "--server",
            "--listen", "127.0.0.1:$port",
        )
        Log.i(TAG, "spawn: $cmd")
        val pb = ProcessBuilder(cmd)
            .directory(workingDir)
            .redirectErrorStream(false)
        pb.environment().putAll(paths.processEnv(workingDir = workingDir))

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            Log.e(TAG, "spawn failed", t)
            return@withContext Result.Failed(
                stage = "spawn",
                message = "${t.javaClass.simpleName}: ${t.message}",
                stderrTail = "",
            )
        }
        Log.i(TAG, "spawned, alive=${proc.isAlive}")

        // Drain stderr into a buffer AND logcat, so on failure we can show
        // the user whatever lldb-server complained about. Own scope — we
        // don't want the Room/IO dispatchers to hold onto this after the
        // spike returns.
        val sideScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stderrBuf = StringBuilder()
        sideScope.launch {
            try {
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        Log.w(TAG, "[lldb-server] $it")
                        synchronized(stderrBuf) {
                            stderrBuf.append(it).append('\n')
                            if (stderrBuf.length > 4000) {
                                stderrBuf.delete(0, stderrBuf.length - 4000)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // stream closed — expected when we kill the process
            }
        }

        fun stderrTail(): String = synchronized(stderrBuf) { stderrBuf.toString() }

        try {
            // 3. Wait for the listener. Poll TCP because lldb-server
            //    doesn't print a machine-parseable "ready" line.
            val socket = connectWithRetry("127.0.0.1", port, timeoutMs = 5000)
                ?: run {
                    // Did it crash?
                    val alive = proc.isAlive
                    return@withContext Result.Failed(
                        stage = "connect",
                        message = "could not connect to 127.0.0.1:$port after 5s " +
                            "(lldb-server alive=$alive)",
                        stderrTail = stderrTail(),
                    )
                }
            Log.i(TAG, "connected tcp://127.0.0.1:$port")

            socket.use { sock ->
                sock.soTimeout = 3000
                val out = sock.getOutputStream()
                val input = sock.getInputStream()

                // 4. Send `$qSupported#37`. 0x37 is the hex checksum byte
                //    for the ASCII string "qSupported" mod 256. Clients
                //    and servers exchange packets framed as `$<body>#<cc>`.
                val packet = "\$qSupported#37"
                Log.i(TAG, "-> $packet")
                out.write(packet.toByteArray(Charsets.US_ASCII))
                out.flush()

                // 5. Read reply. GDB-remote starts with `+` ack, then
                //    `$<body>#<cc>`. We stop after 2 KB or on timeout.
                val buf = ByteArray(2048)
                val sb = StringBuilder()
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 3000) {
                    val n = try {
                        input.read(buf)
                    } catch (_: java.net.SocketTimeoutException) { break }
                    if (n <= 0) break
                    sb.append(String(buf, 0, n, Charsets.US_ASCII))
                    if (sb.contains("#")) break  // full packet received
                }
                val reply = sb.toString()
                Log.i(TAG, "<- $reply")

                val ok = reply.contains("PacketSize=") ||
                    reply.contains("qXfer") ||
                    reply.contains("vCont")
                return@withContext if (ok) {
                    Result.Ok(banner = reply, stderrTail = stderrTail())
                } else {
                    Result.Failed(
                        stage = "protocol",
                        message = "unexpected reply: ${reply.take(200)}",
                        stderrTail = stderrTail(),
                    )
                }
            }
        } finally {
            // Spike scope — always kill the child. Real debugger will
            // manage lifecycle properly.
            try {
                proc.destroyForcibly()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Gdbserver-mode probe. Launches lldb-server in `gdbserver` mode with
     * libdebug_target.so (a canned PIE arm64 binary shipped in jniLibs)
     * as the target — lldb-server will fork, exec, PTRACE_TRACEME the
     * child, and stop at entry. We then drive a short GDB-remote session:
     *
     *   ->  $qSupported#37       (capabilities handshake)
     *   <-  $PacketSize=...#cc
     *   ->  $?#3f                (query initial stop reason)
     *   <-  $T05...#cc           (T packet: thread stopped, signal 05 = SIGTRAP)
     *   ->  $g#67                (read all general-purpose regs)
     *   <-  $<lots of hex>#cc    (~600 hex chars for arm64 GPRs)
     *   ->  $k#6b                (kill + disconnect)
     *
     * Success = all three replies parse. That validates:
     *  1. ptrace works from lldb-server inside untrusted_app_35
     *  2. GDB-remote protocol round-trips over localhost TCP
     *  3. Register read returns non-trivial data
     * Everything after that — breakpoints, stepping, attach-to-dlopened
     * user .so — is the real debugger work, not the spike.
     */
    suspend fun runGdbserver(): Result = withContext(dispatchers.io) {
        Log.i(TAG, "runGdbserver(): starting")
        val paths = toolchain.paths
            ?: return@withContext Result.Failed(
                stage = "toolchain",
                message = "toolchain not installed",
                stderrTail = "",
            )

        val lldbServer = paths.lldbServer
        if (!lldbServer.exists()) {
            return@withContext Result.Failed(
                stage = "resolve",
                message = "lldb-server symlink not found at ${lldbServer.absolutePath}",
                stderrTail = "",
            )
        }

        val target = File(paths.nativeLibDir, "libdebug_target.so")
        if (!target.exists()) {
            return@withContext Result.Failed(
                stage = "resolve",
                message = "libdebug_target.so not found at ${target.absolutePath}",
                stderrTail = "",
            )
        }
        Log.i(TAG, "target: ${target.absolutePath}")

        val port = 5040
        val cmd = listOf(
            lldbServer.absolutePath,
            "gdbserver",
            "127.0.0.1:$port",
            "--",
            target.absolutePath,
        )
        Log.i(TAG, "spawn: $cmd")
        val workingDir = paths.termuxRoot
        val pb = ProcessBuilder(cmd)
            .directory(workingDir)
            .redirectErrorStream(false)
        pb.environment().putAll(paths.processEnv(workingDir = workingDir))

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            Log.e(TAG, "spawn failed", t)
            return@withContext Result.Failed(
                stage = "spawn",
                message = "${t.javaClass.simpleName}: ${t.message}",
                stderrTail = "",
            )
        }
        Log.i(TAG, "spawned, alive=${proc.isAlive}")

        val sideScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stderrBuf = StringBuilder()
        sideScope.launch {
            try {
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        Log.w(TAG, "[lldb-server] $it")
                        synchronized(stderrBuf) {
                            stderrBuf.append(it).append('\n')
                            if (stderrBuf.length > 4000) {
                                stderrBuf.delete(0, stderrBuf.length - 4000)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        fun stderrTail(): String = synchronized(stderrBuf) { stderrBuf.toString() }

        try {
            val socket = connectWithRetry("127.0.0.1", port, timeoutMs = 5000)
                ?: return@withContext Result.Failed(
                    stage = "connect",
                    message = "could not connect to 127.0.0.1:$port after 5s " +
                        "(lldb-server alive=${proc.isAlive})",
                    stderrTail = stderrTail(),
                )
            Log.i(TAG, "connected tcp://127.0.0.1:$port")

            socket.use { sock ->
                sock.soTimeout = 3000
                val out = sock.getOutputStream()
                val input = sock.getInputStream()
                val report = StringBuilder()

                // Step 1: qSupported
                val qSup = exchange(out, input, "qSupported") ?: return@withContext Result.Failed(
                    stage = "qSupported",
                    message = "no reply",
                    stderrTail = stderrTail(),
                )
                Log.i(TAG, "qSupported <- $qSup")
                report.appendLine("qSupported: $qSup")
                if (!qSup.contains("PacketSize=")) {
                    return@withContext Result.Failed(
                        stage = "qSupported",
                        message = "missing PacketSize in reply: $qSup",
                        stderrTail = stderrTail(),
                    )
                }

                // Step 2: ? — query initial stop. lldb-server stops the
                // target at the first instruction after exec, so we should
                // see T<sig> thread status.
                val stopReply = exchange(out, input, "?") ?: return@withContext Result.Failed(
                    stage = "stopReason",
                    message = "no reply to ?",
                    stderrTail = stderrTail(),
                )
                Log.i(TAG, "? <- $stopReply")
                report.appendLine("?        : $stopReply")
                if (!(stopReply.startsWith("T") || stopReply.startsWith("S"))) {
                    return@withContext Result.Failed(
                        stage = "stopReason",
                        message = "unexpected stop reply: $stopReply",
                        stderrTail = stderrTail(),
                    )
                }

                // Step 3: read all GPRs. arm64 = 31 x 64-bit regs + SP + PC
                // + PSTATE ≈ 272 bytes = ~544 hex chars. Not empty.
                val gReply = exchange(out, input, "g") ?: return@withContext Result.Failed(
                    stage = "readRegs",
                    message = "no reply to g",
                    stderrTail = stderrTail(),
                )
                Log.i(TAG, "g <- ${gReply.take(80)}…(${gReply.length} chars)")
                report.appendLine("g        : ${gReply.length} chars, head=${gReply.take(32)}…")
                if (gReply.length < 64 || !gReply.matches(Regex("^[0-9a-fA-F]+$"))) {
                    return@withContext Result.Failed(
                        stage = "readRegs",
                        message = "invalid register payload (len=${gReply.length})",
                        stderrTail = stderrTail(),
                    )
                }

                // Step 4: polite disconnect. `k` kills the inferior and
                // closes the session.
                try {
                    out.write("\$k#6b".toByteArray(Charsets.US_ASCII))
                    out.flush()
                } catch (_: Throwable) {}

                return@withContext Result.Ok(
                    banner = report.toString(),
                    stderrTail = stderrTail(),
                )
            }
        } finally {
            try { proc.destroyForcibly() } catch (_: Throwable) {}
        }
    }

    /**
     * Send a GDB-remote packet body, read and parse one reply packet,
     * acknowledge it with `+`, and return the body (between `$` and `#`).
     * Returns null on timeout or stream close.
     *
     * GDB-remote protocol in default (acked) mode:
     *   client -> $body#cc
     *   server -> +                  (ack of client packet)
     *   server -> $reply#cc
     *   client -> +                  (ack of server reply)  <-- WE NEED THIS
     *
     * Without our trailing `+`, lldb-server waits on the ack, times out,
     * and closes the session after the first exchange. `QStartNoAckMode`
     * turns acks off entirely, but we don't negotiate it yet.
     *
     * This is NOT a real RSP implementation — no RLE decoding, no escape
     * handling, no binary data, no retransmit on `-`. Good enough for
     * spike-level qSupported / ? / g packets.
     */
    private fun exchange(
        out: java.io.OutputStream,
        input: java.io.InputStream,
        body: String,
    ): String? {
        val cc = body.sumOf { it.code } and 0xff
        val pkt = "\$$body#${"%02x".format(cc)}"
        Log.d(TAG, "-> $pkt")
        try {
            out.write(pkt.toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "exchange write failed: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val n = try {
                input.read(buf)
            } catch (t: Throwable) {
                Log.w(TAG, "exchange read failed: ${t.javaClass.simpleName}: ${t.message}")
                return null
            }
            if (n <= 0) {
                Log.w(TAG, "exchange read: EOF (sb so far='${sb.take(60)}')")
                return null
            }
            sb.append(String(buf, 0, n, Charsets.US_ASCII))
            val dollar = sb.indexOf('$')
            if (dollar < 0) continue
            val hash = sb.indexOf('#', startIndex = dollar)
            if (hash >= 0 && hash + 2 < sb.length) {
                // Send our ack for the server's reply — required by the
                // default RSP mode. Without this lldb-server will abandon
                // the session after the first exchange.
                try {
                    out.write('+'.code)
                    out.flush()
                } catch (_: Throwable) { /* best-effort */ }
                return sb.substring(dollar + 1, hash)
            }
        }
        Log.w(TAG, "exchange: timeout waiting for reply to $body")
        return null
    }

    /** Poll `connect(2)` until success or timeout — lldb-server doesn't
     *  announce readiness on a stream we can monitor, so TCP is the only
     *  reliable signal. */
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

    companion object {
        private const val TAG = "cppide-dbgspike"
    }
}
