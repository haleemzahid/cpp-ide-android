package dev.cppide.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.cppide.core.Core
import dev.cppide.core.project.Project
import dev.cppide.core.session.RecentProject
import dev.cppide.ide.screens.editor.EditorRoute
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = (application as CppIdeApp).core

        setContent {
            CppIdeTheme {
                AppNavigation(core = core)
            }
        }
    }
}

/** Top-level screen state — extend this when adding more destinations. */
private sealed interface Destination {
    data object Welcome : Destination
    data class Editor(val project: Project) : Destination
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
                onOpenFolder = { /* TODO: import .zip flow */ },
                onSettings = { /* TODO: settings screen */ },
            )
        }
        is Destination.Editor -> {
            EditorRoute(
                core = core,
                project = current.project,
                onBack = { destination = Destination.Welcome },
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
