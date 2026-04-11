package dev.cppide.ide.screens.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cppide.core.Core
import dev.cppide.core.exercises.Exercise
import dev.cppide.core.exercises.ExerciseCategoryDownload
import dev.cppide.core.project.Project
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the exercises catalog state. On init, fetches every category
 * from the API AND downloads each one's full payload so the flat row
 * list can show exercise titles (not just categories) without a
 * per-tap round-trip. Tradeoff: first open is slower if there are
 * many categories, but the list screen is then fully offline.
 *
 * When the user taps download, we materialise the entire CATEGORY the
 * exercise belongs to as a local project — that matches the UX
 * Shahid confirmed: one category = one project on disk, each exercise
 * as a subfolder.
 */
class ExercisesViewModel(
    private val core: Core,
) : ViewModel() {

    private val _state = MutableStateFlow(ExercisesState())
    val state: StateFlow<ExercisesState> = _state.asStateFlow()

    /** One-shot navigation event fired when a download finishes so
     *  the route can push the editor on top. */
    private val _openProject = MutableSharedFlow<Project>(extraBufferCapacity = 2)
    val openProject: SharedFlow<Project> = _openProject.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null) }

            val catsResult = core.exercisesApi.listCategories()
            catsResult.onFailure { t ->
                _state.update {
                    it.copy(loading = false, errorMessage = "fetch: ${t.message}")
                }
                return@launch
            }

            val categories = catsResult.getOrNull().orEmpty()
                .sortedWith(compareBy({ it.orderIndex }, { it.title }))

            // Download the full payload for every category so we can
            // show exercise titles without another round-trip when the
            // user scrolls. If any category fails, we surface the
            // error but keep the ones that succeeded.
            val rows = mutableListOf<ExerciseRow>()
            var firstError: String? = null
            for (cat in categories) {
                rows.add(ExerciseRow.Header(cat))
                val full = core.exercisesApi.downloadCategory(cat.slug)
                full.onSuccess { payload ->
                    payload.exercises
                        .sortedWith(compareBy({ it.orderIndex }, { it.title }))
                        .forEach { ex ->
                            rows.add(
                                ExerciseRow.Item(
                                    categorySlug = cat.slug,
                                    categoryTitle = cat.title,
                                    exerciseSlug = ex.slug,
                                    exerciseTitle = ex.title,
                                    orderIndex = ex.orderIndex,
                                ),
                            )
                        }
                }.onFailure { t ->
                    if (firstError == null) firstError = "${cat.title}: ${t.message}"
                }
            }

            _state.update {
                it.copy(
                    loading = false,
                    rows = rows,
                    errorMessage = firstError,
                )
            }
        }
    }

    /**
     * Downloads the whole category containing [categorySlug] and
     * materialises it on disk as:
     *
     *   projects/<categorySlug>/<exerciseSlug>/README.md
     *   projects/<categorySlug>/<exerciseSlug>/solution.cpp
     *
     * An empty `solution.cpp` is created so clangd has something to
     * attach to the moment the student opens the exercise folder.
     * After writing the tree, emits an [openProject] event so the
     * route can navigate into the editor.
     *
     * [exerciseSlug] is only used to pin the per-row spinner — the
     * whole category is downloaded in one request regardless.
     */
    fun download(categorySlug: String, exerciseSlug: String) {
        val key = ExercisesState.key(categorySlug, exerciseSlug)
        viewModelScope.launch {
            _state.update { s ->
                s.copy(statusByKey = s.statusByKey + (key to DownloadStatus.Downloading))
            }
            val result = core.exercisesApi.downloadCategory(categorySlug)
            result.onSuccess { payload ->
                val project = runCatching { writeCategoryToDisk(payload) }
                    .getOrElse { t ->
                        _state.update { s ->
                            s.copy(
                                statusByKey = s.statusByKey + (key to DownloadStatus.Failed),
                                errorMessage = "write: ${t.message}",
                            )
                        }
                        return@launch
                    }
                core.sessionRepository.touch(
                    project.root.absolutePath,
                    project.name,
                )
                _state.update { s ->
                    s.copy(statusByKey = s.statusByKey + (key to DownloadStatus.Done))
                }
                _openProject.tryEmit(project)
            }.onFailure { t ->
                _state.update { s ->
                    s.copy(
                        statusByKey = s.statusByKey + (key to DownloadStatus.Failed),
                        errorMessage = "download: ${t.message}",
                    )
                }
            }
        }
    }

    /**
     * Writes a downloaded category payload to app-private storage.
     * Overwrites existing files if the student re-downloads but never
     * clobbers a non-empty `solution.cpp` — that would wipe their work.
     */
    private fun writeCategoryToDisk(payload: ExerciseCategoryDownload): Project {
        val projectsRoot = File(core.context.filesDir, "projects")
        val categoryDir = File(projectsRoot, sanitise(payload.category.slug))
        categoryDir.mkdirs()

        for (exercise in payload.exercises) {
            val exerciseDir = File(categoryDir, sanitise(exercise.slug))
            exerciseDir.mkdirs()

            // README.md always gets replaced — prompt edits from the
            // teacher must reach the student on re-download.
            File(exerciseDir, "README.md").writeText(exercise.promptMd)

            // solution.cpp is created ONLY if missing or empty, so a
            // re-download doesn't nuke in-progress student work.
            val solution = File(exerciseDir, "solution.cpp")
            if (!solution.exists() || solution.length() == 0L) {
                solution.writeText(defaultSolutionFor(exercise))
            }
        }

        return Project(
            name = payload.category.title,
            root = categoryDir.canonicalFile,
            lastOpenedAt = System.currentTimeMillis(),
        )
    }

    private fun defaultSolutionFor(exercise: Exercise): String {
        // Keep the starter neutral — the student writes their own
        // main() or whatever the prompt asks for. The shim still
        // wraps this into a runnable binary via our existing build
        // pipeline, so an empty main is a valid compile.
        return """#include <iostream>

            // ${exercise.title}
            // See README.md for the task.

            int main() {
                return 0;
            }
        """.trimIndent()
    }

    private fun sanitise(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
