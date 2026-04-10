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

    /** Result envelope. `Ok.banner` is the raw string clangd sent back. */
    sealed class Result {
        data class Ok(val banner: String, val stderrTail: String) : Result()
        data class Failed(
            val stage: String,
            val message: String,
            val stderrTail: String,
        ) : Result()
    }

    suspend fun run(): Result = withContext(dispatchers.io) {
        Log.i(TAG, "run(): starting")
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
