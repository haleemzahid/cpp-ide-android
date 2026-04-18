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
 * Owns the exercises catalog state. Lists categories via `/categories`
 * (lightweight) and downloads a whole category at a time via
 * `/categories/:slug/download`. No per-exercise tap pattern — one
 * category = one "Download all" button = one on-disk project with
 * every exercise materialised as a subfolder, matching the curriculum
 * workflow Shahid described.
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

    /** Fired when the user tries to download without a session —
     *  the route routes to the Auth screen and the user can come back. */
    private val _requireLogin = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requireLogin: SharedFlow<Unit> = _requireLogin.asSharedFlow()

    private val projectsRoot = File(core.context.filesDir, "projects")

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null) }
            core.exercisesApi.listCategories()
                .onSuccess { categories ->
                    // Source-of-truth `Done` status comes from the on-disk
                    // folder; a category whose folder was deleted (e.g.
                    // from the Welcome screen) must fall back to
                    // `Download`, not linger as `Done`. Transient user-
                    // initiated states (Downloading, Failed) are kept so
                    // an in-flight download's progress UI survives a
                    // pull-to-refresh.
                    val onDisk = categories
                        .filter { File(projectsRoot, sanitise(it.slug)).exists() }
                        .associate { it.slug to DownloadStatus.Done }
                    val inFlight = _state.value.statusBySlug.filterValues { s ->
                        s == DownloadStatus.Downloading || s == DownloadStatus.Failed
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            categories = categories.sortedWith(
                                compareBy({ it.orderIndex }, { it.title }),
                            ),
                            statusBySlug = onDisk + inFlight,
                        )
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(loading = false, errorMessage = t.message)
                    }
                }
        }
    }

    /**
     * Download every exercise in [categorySlug] and materialise the
     * whole set on disk:
     *
     *   projects/<categorySlug>/<exerciseSlug>/README.md
     *   projects/<categorySlug>/<exerciseSlug>/solution.cpp
     *
     * An empty `solution.cpp` is created next to each README so clangd
     * has something to attach to immediately. After writing the tree,
     * emits an [openProject] event so the route can navigate into the
     * editor with the whole category as a single multi-folder project.
     */
    fun downloadCategory(categorySlug: String) {
        // Downloads are server-authenticated (the catalog API requires a
        // bearer token even for public categories, because the response
        // embeds per-student progress). Route the user through Auth
        // before kicking off any network work.
        if (core.studentAuth.token == null) {
            _requireLogin.tryEmit(Unit)
            return
        }
        viewModelScope.launch {
            _state.update { s ->
                s.copy(statusBySlug = s.statusBySlug + (categorySlug to DownloadStatus.Downloading))
            }
            val result = core.exercisesApi.downloadCategory(categorySlug)
            result.onSuccess { payload ->
                val project = runCatching { writeCategoryToDisk(payload) }
                    .getOrElse { t ->
                        _state.update { s ->
                            s.copy(
                                statusBySlug = s.statusBySlug + (categorySlug to DownloadStatus.Failed),
                                errorMessage = "write: ${t.message}",
                            )
                        }
                        return@launch
                    }
                // Touch the recents list so the project appears on the
                // welcome screen, but do NOT auto-navigate — the user
                // wants to stay on the catalog so they can queue up
                // more downloads. The "Downloaded — tap to open" state
                // on the card lets them jump in when they're ready.
                // Restore saved solutions from the server (cloud backup).
                core.solutionsApi.getByCategory(categorySlug)
                    .onSuccess { saved ->
                        for (solution in saved) {
                            val file = File(
                                project.root,
                                "${sanitise(solution.exerciseSlug)}/solution.cpp",
                            )
                            if (file.exists()) {
                                file.writeText(solution.content)
                            }
                        }
                    }
                core.sessionRepository.touch(
                    project.root.absolutePath,
                    project.name,
                )
                _state.update { s ->
                    s.copy(statusBySlug = s.statusBySlug + (categorySlug to DownloadStatus.Done))
                }
            }.onFailure { t ->
                _state.update { s ->
                    s.copy(
                        statusBySlug = s.statusBySlug + (categorySlug to DownloadStatus.Failed),
                        errorMessage = "download: ${t.message}",
                    )
                }
            }
        }
    }

    /**
     * Open an already-downloaded category in the editor. Called when
     * the user taps the "Downloaded — tap to open" state on a card.
     * Looks up the on-disk folder and emits an [openProject] event
     * without re-hitting the network.
     */
    fun openDownloaded(categorySlug: String) {
        val projectsRoot = File(core.context.filesDir, "projects")
        val categoryDir = File(projectsRoot, sanitise(categorySlug))
        if (!categoryDir.exists()) {
            _state.update { it.copy(errorMessage = "project not found on disk") }
            return
        }
        val title = _state.value.categories
            .firstOrNull { it.slug == categorySlug }?.title
            ?: categoryDir.name
        _openProject.tryEmit(
            Project(
                name = title,
                root = categoryDir.canonicalFile,
                lastOpenedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Writes a downloaded category payload to app-private storage.
     * Overwrites README.md on re-download (teacher edits must reach
     * students) but never clobbers a non-empty `solution.cpp` — that
     * would wipe in-progress student work.
     */
    private fun writeCategoryToDisk(payload: ExerciseCategoryDownload): Project {
        val projectsRoot = File(core.context.filesDir, "projects")
        val categoryDir = File(projectsRoot, sanitise(payload.category.slug))
        categoryDir.mkdirs()

        for (exercise in payload.exercises) {
            val exerciseDir = File(categoryDir, sanitise(exercise.slug))
            exerciseDir.mkdirs()

            File(exerciseDir, "README.md").writeText(exercise.promptMd)

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
        // Starter template written to solution.cpp. Uses trimMargin + `|`
        // so the indentation inside the string is independent of the
        // surrounding Kotlin indentation — what you see here is exactly
        // what lands on disk, with a trailing newline.
        return """
            |#include <iostream>
            |using namespace std;
            |
            |// ${exercise.title}
            |// See README.md for the task.
            |
            |int main() {
            |    return 0;
            |}
            |
        """.trimMargin()
    }

    private fun sanitise(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
