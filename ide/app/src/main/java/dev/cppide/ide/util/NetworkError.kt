package dev.cppide.ide.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Map a raw throwable from our HTTP layer to a user-friendly message.
 * Keeps chat/upload/auth errors readable — the default `t.message`
 * surfaces things like "java.net.UnknownHostException: api.example.com"
 * which looks like a crash to an end user.
 */
fun friendlyNetworkError(t: Throwable, prefix: String = "Request failed"): String = when {
    t is UnknownHostException ||
        t is ConnectException ||
        (t is IOException && t.message?.contains("Unable to resolve host") == true) ->
        "No internet connection. Please check your network and try again."
    t is SocketTimeoutException ->
        "Network timed out. Please check your connection and try again."
    t.message?.startsWith("HTTP 401") == true ||
        t.message?.contains("not authenticated") == true ->
        "Please log in to continue."
    t.message?.startsWith("HTTP 5") == true ->
        "Server is unavailable. Please try again in a moment."
    else -> "$prefix: ${t.message ?: "unknown error"}"
}
