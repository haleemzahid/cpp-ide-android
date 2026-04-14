package dev.cppide.ide.util

import java.text.DateFormat
import java.util.Date

/** Format a millisecond epoch timestamp as a short relative string. */
fun formatRelativeTime(epochMs: Long): String {
    val deltaMs = System.currentTimeMillis() - epochMs
    val mins = deltaMs / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        mins < 60 * 24 * 7 -> "${mins / (60 * 24)}d ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))
    }
}

/** Parse an ISO-8601 timestamp and format as a short relative string. */
fun formatRelativeIso(iso: String): String {
    return try {
        val instant = java.time.Instant.parse(iso)
        formatRelativeTime(instant.toEpochMilli())
    } catch (_: Exception) {
        ""
    }
}
