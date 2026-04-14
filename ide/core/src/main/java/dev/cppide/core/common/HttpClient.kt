package dev.cppide.core.common

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal const val HTTP_CONNECT_TIMEOUT = 10_000
internal const val HTTP_READ_TIMEOUT = 15_000
internal const val USER_AGENT = "cpp-ide-android/0.1"

internal fun httpGet(url: String, token: String? = null): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = HTTP_CONNECT_TIMEOUT
        readTimeout = HTTP_READ_TIMEOUT
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", USER_AGENT)
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return readResponse(conn, url)
}

internal fun httpPost(url: String, jsonBody: String, token: String? = null): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = HTTP_CONNECT_TIMEOUT
        readTimeout = HTTP_READ_TIMEOUT
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("User-Agent", USER_AGENT)
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
        doOutput = true
    }
    try {
        conn.outputStream.use { it.write(jsonBody.toByteArray()) }
        return readResponse(conn, url)
    } finally {
        conn.disconnect()
    }
}

private fun readResponse(conn: HttpURLConnection, url: String): String {
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(body).optString("error", body.take(200))
            } catch (_: Exception) {
                body.take(200)
            }
            error("HTTP $code from $url: $msg")
        }
        return body
    } finally {
        conn.disconnect()
    }
}
