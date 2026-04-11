package dev.cppide.ide.screens.exercises

import dev.cppide.core.exercises.ExerciseCategory

/** Per-category download status. */
enum class DownloadStatus {
    Idle,
    Downloading,
    Done,
    Failed,
}

/**
 * Exercises catalog state. The screen shows one card per category;
 * each card has a single "Download all" button so the student never
 * has to tap through 100 individual exercises. Download status is
 * tracked per category slug.
 */
data class ExercisesState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val categories: List<ExerciseCategory> = emptyList(),
    val statusBySlug: Map<String, DownloadStatus> = emptyMap(),
)
