package dev.cppide.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Collects [flow] and writes each UTF-8-encoded chunk to [out], flushing
 * after every write so the consumer sees bytes immediately. Closes [out]
 * before returning.
 *
 * Maps a broken pipe (reader closed its end) to a [CancellationException]
 * so the caller's `try / catch (CancellationException)` handles both
 * "flow complete" and "reader gone" the same way.
 */
suspend fun Flow<String>.pumpUtf8Into(out: OutputStream) {
    out.use { stream ->
        collect { chunk ->
            try {
                stream.write(chunk.toByteArray(StandardCharsets.UTF_8))
                stream.flush()
            } catch (_: Throwable) {
                throw CancellationException("pipe closed by reader")
            }
        }
    }
}
