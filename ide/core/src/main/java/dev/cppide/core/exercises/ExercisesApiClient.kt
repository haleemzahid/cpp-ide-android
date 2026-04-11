package dev.cppide.core.exercises

import dev.cppide.core.BuildConfig
import dev.cppide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client for the exercises API. Uses [HttpURLConnection] + the
 * built-in `org.json` parser so we don't pull OkHttp / Retrofit /
 * kotlinx-serialization just for two GET endpoints — keeps the APK a
 * few MB smaller and the dependency graph shallower.
 *
 * Base URL comes from [BuildConfig.EXERCISES_API_URL], which is driven
 * by the `exercisesApiUrl` property in `local.properties` (with a
 * hardcoded Dokploy URL as the fallback default). That means a fresh
 * `git clone` + `gradle assembleDebug` Just Works, while a developer
 * pointing at a local server only needs one line in local.properties.
 *
 * All calls suspend on [DispatcherProvider.io] so the underlying
 * blocking socket never runs on the main thread.
 */
class ExercisesApiClient(
    private val dispatchers: DispatcherProvider,
    private val baseUrl: String = BuildConfig.EXERCISES_API_URL,
) {
    /** `GET /categories` — lightweight catalog listing for the Welcome screen. */
    suspend fun listCategories(): Result<List<ExerciseCategory>> =
        withContext(dispatchers.io) {
            runCatching {
                val body = httpGet("$baseUrl/categories")
                val root = JSONObject(body)
                val arr = root.getJSONArray("categories")
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            ExerciseCategory(
                                id = o.getLong("id"),
                                slug = o.getString("slug"),
                                title = o.getString("title"),
                                description = o.optStringOrNull("description"),
                                orderIndex = o.optInt("orderIndex", 0),
                                exerciseCount = o.optInt("exerciseCount", 0),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * `GET /categories/:slug/download` — full payload for one category.
     * The mobile app loops through [ExerciseCategoryDownload.exercises]
     * and writes each to disk as a subfolder.
     */
    suspend fun downloadCategory(slug: String): Result<ExerciseCategoryDownload> =
        withContext(dispatchers.io) {
            runCatching {
                val body = httpGet("$baseUrl/categories/$slug/download")
                val root = JSONObject(body)
                val cat = root.getJSONObject("category")
                val exArr = root.getJSONArray("exercises")
                ExerciseCategoryDownload(
                    category = ExerciseCategory(
                        // `download` endpoint doesn't include id — harmless,
                        // the mobile side never uses the numeric pk.
                        id = -1L,
                        slug = cat.getString("slug"),
                        title = cat.getString("title"),
                        description = cat.optStringOrNull("description"),
                        orderIndex = cat.optInt("orderIndex", 0),
                        exerciseCount = exArr.length(),
                    ),
                    exercises = buildList(exArr.length()) {
                        for (i in 0 until exArr.length()) {
                            val o = exArr.getJSONObject(i)
                            add(
                                Exercise(
                                    slug = o.getString("slug"),
                                    title = o.getString("title"),
                                    promptMd = o.getString("promptMd"),
                                    orderIndex = o.optInt("orderIndex", 0),
                                ),
                            )
                        }
                    },
                )
            }
        }

    /** `GET /health` probe — useful for a "test connection" settings button. */
    suspend fun ping(): Result<Unit> =
        withContext(dispatchers.io) {
            runCatching { httpGet("$baseUrl/health"); Unit }
        }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "cpp-ide-android/0.1")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                error("HTTP $code from $url: ${body.take(200)}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * `org.json` returns the literal string "null" for missing optional
 * string fields — this helper turns that mess into a clean Kotlin null.
 */
private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return value.takeIf { it.isNotEmpty() }
}
