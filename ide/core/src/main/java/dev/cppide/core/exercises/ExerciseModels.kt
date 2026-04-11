package dev.cppide.core.exercises

/**
 * Lightweight category metadata returned by `GET /categories`.
 * Does NOT include exercise content — the catalog screen renders these
 * as a scrollable list, and the full payload is fetched on-demand when
 * the student actually picks a category to download.
 */
data class ExerciseCategory(
    val id: Long,
    val slug: String,
    val title: String,
    val description: String?,
    val orderIndex: Int,
    val exerciseCount: Int,
)

/**
 * A single exercise inside a downloaded category bundle. The student
 * sees the rendered markdown ([promptMd]) as the task description; the
 * mobile app materialises this into `<category>/<slug>/README.md` plus
 * a freshly created empty `solution.cpp` that the student then edits.
 */
data class Exercise(
    val slug: String,
    val title: String,
    val promptMd: String,
    val orderIndex: Int,
)

/**
 * Full response of `GET /categories/:slug/download`. One request per
 * category — the whole exercise set arrives in a single JSON payload
 * so the mobile app can write the project tree offline in one shot.
 */
data class ExerciseCategoryDownload(
    val category: ExerciseCategory,
    val exercises: List<Exercise>,
)
