package dev.cppide.core.exercises

import dev.cppide.core.BuildConfig
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.common.httpGet
import dev.cppide.core.common.optStringOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Thin HTTP client for the exercises API. Uses the shared [httpGet]
 * utility so timeout/user-agent config is centralised.
 *
 * Base URL comes from [BuildConfig.EXERCISES_API_URL], driven by
 * `exercisesApiUrl` in `local.properties`.
 */
class ExercisesApiClient(
    private val dispatchers: DispatcherProvider,
    private val baseUrl: String = BuildConfig.EXERCISES_API_URL,
) {
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

    suspend fun downloadCategory(slug: String): Result<ExerciseCategoryDownload> =
        withContext(dispatchers.io) {
            runCatching {
                val body = httpGet("$baseUrl/categories/$slug/download")
                val root = JSONObject(body)
                val cat = root.getJSONObject("category")
                val exArr = root.getJSONArray("exercises")
                ExerciseCategoryDownload(
                    category = ExerciseCategory(
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

    suspend fun ping(): Result<Unit> =
        withContext(dispatchers.io) {
            runCatching { httpGet("$baseUrl/health"); Unit }
        }
}
