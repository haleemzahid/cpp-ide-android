package dev.cppide.core.common

import org.json.JSONObject

/**
 * `org.json` returns the literal string "null" for missing optional
 * string fields — this helper turns that into a clean Kotlin null.
 */
fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return value.takeIf { it.isNotEmpty() }
}
