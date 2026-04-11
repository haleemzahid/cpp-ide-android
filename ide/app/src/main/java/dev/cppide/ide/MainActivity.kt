package dev.cppide.ide

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.cppide.core.Core
import dev.cppide.core.project.Project
import dev.cppide.core.session.RecentProject
import dev.cppide.ide.screens.about.AboutRoute
import dev.cppide.ide.screens.chat.ChatRoute
import dev.cppide.ide.screens.editor.EditorRoute
import dev.cppide.ide.screens.exercises.ExercisesRoute
import dev.cppide.ide.screens.settings.SettingsRoute
import dev.cppide.ide.screens.welcome.NewProjectDialog
import dev.cppide.ide.screens.welcome.WelcomeRoute
import dev.cppide.ide.theme.CppIdeTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single hosting activity. The whole app is one Compose tree under
 * [CppIdeTheme]. Screen navigation is a tiny state machine here — we'll
 * graduate to Jetpack Navigation Compose once there are more than a
 * handful of screens.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = (application as CppIdeApp).core

        maybeRequestNotificationPermission()

        setContent {
            CppIdeTheme {
                AppNavigation(core = core)
            }
        }
    }

    /**
     * On Android 13+ the system silently drops foreground-service progress
     * notifications unless POST_NOTIFICATIONS has been granted. Ask once
     * on launch — the user can still deny; the download service works
     * without a visible notification, just less informatively.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Top-level screen state — extend this when adding more destinations. */
private sealed interface Destination {
    data object Welcome : Destination
    data class Editor(val project: Project) : Destination
    data object Settings : Destination
    data object Chat : Destination
    data object About : Destination
    data object Exercises : Destination
}

@Composable
private fun AppNavigation(core: Core) {
    var destination by remember { mutableStateOf<Destination>(Destination.Welcome) }
    var showNewProject by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when (val current = destination) {
        Destination.Welcome -> {
            WelcomeRoute(
                core = core,
                onOpenProject = { recent ->
                    val project = recent.toProject()
                    destination = Destination.Editor(project)
                },
                onCreateNew = { showNewProject = true },
                onOpenExercises = { destination = Destination.Exercises },
                onAbout = { destination = Destination.About },
                onChat = { destination = Destination.Chat },
                onSettings = { destination = Destination.Settings },
            )
        }
        is Destination.Editor -> {
            EditorRoute(
                core = core,
                project = current.project,
                onBack = { destination = Destination.Welcome },
            )
        }
        Destination.Settings -> {
            SettingsRoute(
                core = core,
                onBack = { destination = Destination.Welcome },
            )
        }
        Destination.Chat -> {
            ChatRoute(
                core = core,
                onBack = { destination = Destination.Welcome },
                onOpenSettings = { destination = Destination.Settings },
            )
        }
        Destination.About -> {
            AboutRoute(
                onBack = { destination = Destination.Welcome },
            )
        }
        Destination.Exercises -> {
            ExercisesRoute(
                core = core,
                onBack = { destination = Destination.Welcome },
                onOpenProject = { project ->
                    destination = Destination.Editor(project)
                },
            )
        }
    }

    if (showNewProject) {
        NewProjectDialog(
            onDismiss = { showNewProject = false },
            onCreate = { name ->
                showNewProject = false
                scope.launch {
                    val project = createProject(core, name)
                    if (project != null) {
                        core.sessionRepository.touch(project.root.absolutePath, project.name)
                        destination = Destination.Editor(project)
                    }
                }
            },
        )
    }
}

/**
 * Creates a project directory under app-private storage with a starter
 * main.cpp. Returns null on failure (caller can show a snackbar later).
 */
private suspend fun createProject(core: Core, name: String): Project? {
    val safeName = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    val root = File(File(core.context.filesDir, "projects"), safeName)
    val opened = core.projectService.open(root, name).getOrNull() ?: return null
    core.projectService.createFile(
        relativePath = "main.cpp",
        content = STARTER_MAIN_CPP,
    )
    return opened
}

private fun RecentProject.toProject() = Project(
    name = displayName,
    root = File(rootPath),
    lastOpenedAt = lastOpenedAt,
)

private val STARTER_MAIN_CPP = """#include <iostream>
using namespace std;

int main(int argc, char** argv) {
    cout << "Hello, world!" << endl;
    return 0;
}
""".trimIndent()
