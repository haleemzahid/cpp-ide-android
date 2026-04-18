package dev.cppide.core.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Debug Adapter Protocol (DAP) client over a pair of byte streams.
 *
 * DAP is a JSON-RPC-ish protocol where each message is a UTF-8 JSON
 * object framed by a `Content-Length` header:
 *
 *     Content-Length: 123\r\n
 *     \r\n
 *     {"type":"request","seq":1,"command":"initialize", ... }
 *
 * Three message types flow over the wire:
 *
 *   - **request**  — client → server; has a unique `seq`.
 *   - **response** — server → client; `request_seq` echoes the request's `seq`.
 *   - **event**    — server → client; unsolicited, e.g. `stopped`, `output`.
 *
 * This class owns one reader coroutine that pulls frames off the input
 * stream and routes them:
 *
 *   - responses → the [CompletableDeferred] waiting on that request's seq
 *   - events    → the [events] SharedFlow for subscribers
 *
 * Writes are serialized via a Mutex so concurrent [sendRequest] calls
 * don't interleave their frames on the wire.
 *
 * Lifecycle:
 *   1. Construct with the process's stdin/stdout.
 *   2. Call [start] exactly once — spawns the reader.
 *   3. Use [sendRequest] / observe [events] for the session.
 *   4. Call [close] when done — cancels the reader, closes streams,
 *      fails all in-flight requests with [ClosedException].
 *
 * Thread-safety: [sendRequest] is safe to call from any coroutine.
 * [start] / [close] must not race each other.
 */
class DapConnection(
    private val input: InputStream,
    rawOutput: OutputStream,
) {

    private val output: OutputStream = BufferedOutputStream(rawOutput)

    /** Monotonic request sequence number, assigned by [sendRequest]. */
    private val nextSeq = AtomicInteger(1)

    /** In-flight requests: seq → deferred that completes with the body of the response. */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    /** Serializes writes so concurrent requests don't interleave their bytes. */
    private val writeLock = Mutex()

    /** Fired for every DAP `event`. LldbDapDebuggerService subscribes. */
    private val _events = MutableSharedFlow<JSONObject>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<JSONObject> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readerJob: Job? = null
    @Volatile private var closed = false

    /** Starts the background reader. Idempotent — second call is a no-op. */
    fun start() {
        if (readerJob != null) return
        readerJob = scope.launch { readLoop() }
    }

    /**
     * Sends a DAP request and suspends until the matching response arrives,
     * or until [timeoutMs] elapses, or until the connection is closed.
     *
     * Returns the full response object. Callers are expected to inspect
     * `success` and `body` fields themselves — this method does not throw
     * on `success: false` because some DAP servers (including lldb-dap)
     * rely on the client interpreting failures contextually.
     */
    suspend fun sendRequest(
        command: String,
        arguments: JSONObject? = null,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): JSONObject {
        if (closed) throw ClosedException("DapConnection is closed")

        val seq = nextSeq.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[seq] = deferred

        val envelope = JSONObject().apply {
            put("seq", seq)
            put("type", "request")
            put("command", command)
            if (arguments != null) put("arguments", arguments)
        }

        try {
            writeLock.withLock { writeFrame(envelope.toString()) }
        } catch (t: Throwable) {
            pending.remove(seq)
            throw t
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(seq)
        }
    }

    /**
     * Closes the streams, cancels the reader, and fails every in-flight
     * request with [ClosedException]. Idempotent.
     */
    fun close() {
        if (closed) return
        closed = true
        val err = ClosedException("DapConnection closed")
        pending.values.toList().forEach { it.completeExceptionally(err) }
        pending.clear()
        try { input.close() } catch (_: Throwable) {}
        try { output.close() } catch (_: Throwable) {}
        scope.cancel()
    }

    // ---- reader loop ----

    private suspend fun readLoop() {
        try {
            while (scope.isActive && !closed) {
                val body = readFrame() ?: break
                val msg = try {
                    JSONObject(body)
                } catch (t: Throwable) {
                    // Malformed frame — skip and keep reading. In practice
                    // lldb-dap always emits valid JSON; this is defensive.
                    continue
                }
                when (msg.optString("type")) {
                    "response" -> {
                        val requestSeq = msg.optInt("request_seq", -1)
                        val waiter = pending.remove(requestSeq)
                        waiter?.complete(msg)
                    }
                    "event" -> {
                        _events.tryEmit(msg)
                    }
                    else -> {
                        // Unknown type — log-worthy in debug, ignored in release.
                    }
                }
            }
        } catch (_: CancellationException) {
            // normal shutdown
        } catch (t: Throwable) {
            // Stream died. Fail any in-flight requests so callers unblock.
            val err = ClosedException("DapConnection reader died: ${t.message}", t)
            pending.values.toList().forEach { it.completeExceptionally(err) }
            pending.clear()
        }
    }

    // ---- framing ----

    /**
     * Reads one Content-Length-framed message from [input]. Returns the
     * JSON body as a UTF-8 string, or null on clean EOF.
     *
     * Only the `Content-Length` header is required; any other headers
     * (e.g. Content-Type, which DAP technically allows) are skipped.
     */
    private fun readFrame(): String? {
        var contentLength = -1
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) break  // blank line terminates headers
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                contentLength = value.toIntOrNull() ?: return null
            }
        }
        if (contentLength <= 0) return null
        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(buf, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return String(buf, StandardCharsets.UTF_8)
    }

    /** Reads one ASCII header line terminated by \r\n. Null on EOF. */
    private fun readHeaderLine(): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty() && prev < 0) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                return sb.substring(0, sb.length - 1)
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    /** Writes one Content-Length-framed message. Caller holds [writeLock]. */
    private fun writeFrame(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    class ClosedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
    }
}
