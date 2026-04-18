package dev.cppide.ide.screens.welcome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.cppide.core.Core
import dev.cppide.core.session.RecentProject
import dev.cppide.ide.components.BodyText
import dev.cppide.ide.components.CppDialog
import dev.cppide.ide.util.friendlyNetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun WelcomeRoute(
    core: Core,
    onOpenProject: (RecentProject) -> Unit,
    onOpenRecentFile: (dev.cppide.core.session.RecentFile) -> Unit,
    onCreateNew: () -> Unit,
    onOpenExercises: () -> Unit,
    onOpenQuestions: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
    onLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val recents by core.sessionRepository
        .recentProjects()
        .collectAsState(initial = emptyList())
    val recentFiles by core.sessionRepository
        .recentFiles(limit = 10)
        .collectAsState(initial = emptyList())
    val session by core.studentAuth.session.collectAsState()
    var totalUnread by remember { mutableIntStateOf(0) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadResult by remember { mutableStateOf<String?>(null) }
    var showLoginRequired by remember { mutableStateOf(false) }

    LaunchedEffect(session?.student?.id) {
        if (session != null) {
            core.chatApi.unreadSummary().onSuccess { entries ->
                totalUnread = entries.sumOf { it.unreadCount }
            }
        } else {
            totalUnread = 0
        }
    }

    val isLoggedIn = session != null

    WelcomeScreen(
        recents = recents,
        recentFiles = recentFiles,
        studentName = session?.student?.displayName,
        isLoggedIn = isLoggedIn,
        totalUnread = totalUnread,
        isUploading = isUploading,
        uploadResult = uploadResult,
        onOpenProject = { project ->
            scope.launch {
                core.sessionRepository.touch(project.rootPath, project.displayName)
                onOpenProject(project)
            }
        },
        onOpenRecentFile = { file -> onOpenRecentFile(file) },
        onTogglePin = { project ->
            scope.launch {
                core.sessionRepository.setPinned(project.rootPath, !project.pinned)
            }
        },
        onDeleteProject = { project ->
            scope.launch {
                runCatching {
                    File(project.rootPath).deleteRecursively()
                }
                core.sessionRepository.forget(project.rootPath)
            }
        },
        onCreateNew = onCreateNew,
        onOpenExercises = onOpenExercises,
        onUploadSolutions = {
            if (!isLoggedIn) {
                showLoginRequired = true
                return@WelcomeScreen
            }
            isUploading = true
            uploadResult = null
            scope.launch {
                val solutions = collectAllSolutions(core)
                if (solutions.isEmpty()) {
                    uploadResult = "No exercises to upload"
                } else {
                    core.solutionsApi.upload(solutions)
                        .onSuccess { count -> uploadResult = "Uploaded $count exercise${if (count != 1) "s" else ""}" }
                        .onFailure { t -> uploadResult = friendlyNetworkError(t, "Upload failed") }
                }
                isUploading = false
            }
        },
        onOpenQuestions = onOpenQuestions,
        onAbout = onAbout,
        onLogout = {
            core.studentAuth.logout()
            onLogout()
        },
        onLogin = onLogin,
    )

    if (showLoginRequired) {
        CppDialog(
            title = "Login required",
            onDismiss = { showLoginRequired = false },
            confirmText = "Log in",
            onConfirm = {
                showLoginRequired = false
                onLogin()
            },
            dismissText = "Not now",
        ) {
            BodyText(
                text = "Log in to upload your progress so it can be saved and reviewed.",
            )
        }
    }
}

/**
 * Walk the projects directory and collect all solution.cpp files.
 * Returns a list of (categorySlug, exerciseSlug, content).
 */
private suspend fun collectAllSolutions(core: Core): List<Triple<String, String, String>> = withContext(Dispatchers.IO) {
    val projectsDir = File(core.context.filesDir, "projects")
    if (!projectsDir.exists()) return@withContext emptyList()

    val results = mutableListOf<Triple<String, String, String>>()
    for (categoryDir in projectsDir.listFiles().orEmpty()) {
        if (!categoryDir.isDirectory) continue
        for (exerciseDir in categoryDir.listFiles().orEmpty()) {
            if (!exerciseDir.isDirectory) continue
            val solution = File(exerciseDir, "solution.cpp")
            if (solution.exists() && solution.length() > 0) {
                results.add(Triple(categoryDir.name, exerciseDir.name, solution.readText()))
            }
        }
    }
    results
}
