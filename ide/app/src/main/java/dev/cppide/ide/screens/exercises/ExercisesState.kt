package dev.cppide.ide.screens.exercises

import dev.cppide.core.exercises.ExerciseCategory

/**
 * Flat entry shown in the exercises list. Each row is either a
 * section header (category title) or an exercise belonging to the
 * most-recent header above it. Kept flat because it renders cleanly
 * in a single LazyColumn with mixed item types — no nested lazy
 * layouts, which Compose doesn't allow.
 */
sealed interface ExerciseRow {
    data class Header(val category: ExerciseCategory) : ExerciseRow
    data class Item(
        val categorySlug: String,
        val categoryTitle: String,
        val exerciseSlug: String,
        val exerciseTitle: String,
        val orderIndex: Int,
    ) : ExerciseRow
}

/**
 * Per-exercise download status. Tracked in a map keyed by
 * `<categorySlug>/<exerciseSlug>` so the UI can show a spinner on the
 * specific row being worked on without re-fetching anything.
 */
enum class DownloadStatus {
    Idle,
    Downloading,
    Done,
    Failed,
}

data class ExercisesState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val rows: List<ExerciseRow> = emptyList(),
    val statusByKey: Map<String, DownloadStatus> = emptyMap(),
) {
    companion object {
        fun key(categorySlug: String, exerciseSlug: String): String =
            "$categorySlug/$exerciseSlug"
    }
}
