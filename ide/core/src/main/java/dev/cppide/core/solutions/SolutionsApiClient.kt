package dev.cppide.core.solutions

import dev.cppide.core.BuildConfig
import dev.cppide.core.common.DispatcherProvider
import dev.cppide.core.common.httpGet
import dev.cppide.core.common.httpPost
import dev.cppide.core.student.StudentAuthClient
import dev.cppide.core.student.requireToken
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SavedSolution(
    val exerciseSlug: String,
    val content: String,
    val updatedAt: String,
)

data class SolutionRef(
    val categorySlug: String,
    val exerciseSlug: String,
    val updatedAt: String,
)

class SolutionsApiClient(
    private val dispatchers: DispatcherProvider,
    private val studentAuth: StudentAuthClient,
    private val baseUrl: String = BuildConfig.EXERCISES_API_URL,
) {
    private fun authToken(): String = studentAuth.requireToken()

    /**
     * Upload all solutions from the given list.
     * Each entry is (categorySlug, exerciseSlug, content).
     */
    suspend fun upload(
        solutions: List<Triple<String, String, String>>,
    ): Result<Int> = withContext(dispatchers.io) {
        runCatching {
            val arr = JSONArray()
            for ((cat, ex, content) in solutions) {
                arr.put(JSONObject().apply {
                    put("categorySlug", cat)
                    put("exerciseSlug", ex)
                    put("content", content)
                })
            }
            val body = JSONObject().put("solutions", arr).toString()
            val resp = httpPost("$baseUrl/solutions/upload", body, authToken())
            JSONObject(resp).getInt("uploaded")
        }
    }

    /** List all saved solutions (no content, just slugs + timestamps). */
    suspend fun list(): Result<List<SolutionRef>> = withContext(dispatchers.io) {
        runCatching {
            val body = httpGet("$baseUrl/solutions", authToken())
            val arr = JSONObject(body).getJSONArray("solutions")
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(SolutionRef(
                        categorySlug = o.getString("categorySlug"),
                        exerciseSlug = o.getString("exerciseSlug"),
                        updatedAt = o.getString("updatedAt"),
                    ))
                }
            }
        }
    }

    /** Get all saved solutions for a category WITH content (for restore). */
    suspend fun getByCategory(categorySlug: String): Result<List<SavedSolution>> =
        withContext(dispatchers.io) {
            runCatching {
                val body = httpGet("$baseUrl/solutions/by-category/$categorySlug", authToken())
                val arr = JSONObject(body).getJSONArray("solutions")
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(SavedSolution(
                            exerciseSlug = o.getString("exerciseSlug"),
                            content = o.getString("content"),
                            updatedAt = o.getString("updatedAt"),
                        ))
                    }
                }
            }
        }
}
