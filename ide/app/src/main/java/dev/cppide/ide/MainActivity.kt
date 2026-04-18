package dev.cppide.ide

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.cppide.core.Core
import dev.cppide.core.project.Project
import dev.cppide.core.session.RecentProject
import dev.cppide.ide.screens.about.AboutRoute
import dev.cppide.ide.screens.auth.AuthRoute
import dev.cppide.ide.screens.editor.EditorRoute
import dev.cppide.ide.screens.exercises.ExercisesRoute
import dev.cppide.ide.screens.onboarding.OnboardingScreen
import dev.cppide.ide.screens.questions.QuestionsRoute
import dev.cppide.ide.screens.welcome.NewProjectDialog
import dev.cppide.ide.screens.welcome.WelcomeRoute
import dev.cppide.ide.util.slugToTitle
import dev.cppide.ide.theme.CppIdeTheme
import kotlinx.coroutines.launch
import java.io.File

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

private object Routes {
    const val ONBOARDING = "onboarding"
    const val AUTH = "auth"
    const val WELCOME = "welcome"
    const val ABOUT = "about"
    const val EXERCISES = "exercises"
    const val QUESTIONS = "questions"

    const val EDITOR = "editor/{rootPath}/{name}?openFile={openFile}&openChat={openChat}"
    fun editor(
        project: Project,
        openFile: String? = null,
        openChat: Boolean = false,
    ): String {
        val root = Uri.encode(project.root.absolutePath)
        val name = Uri.encode(project.name)
        var route = "editor/$root/$name"
        val params = mutableListOf<String>()
        if (openFile != null) params.add("openFile=${Uri.encode(openFile)}")
        if (openChat) params.add("openChat=true")
        if (params.isNotEmpty()) route += "?" + params.joinToString("&")
        return route
    }
}

@Composable
private fun AppNavigation(core: Core) {
    val navController = rememberNavController()
    var showNewProject by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Check onboarding + auth state on launch.
    var authChecked by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(false) }
    val prefs = remember {
        core.context.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE)
    }
    val hasSeenOnboarding = remember { prefs.getBoolean("seen", false) }
    val hasSkippedAuth = remember { prefs.getBoolean("auth_skipped", false) }

    LaunchedEffect(Unit) {
        isLoggedIn = core.studentAuth.tryRestore()
        authChecked = true
    }

    if (!authChecked) return

    // Login is optional. First launch after onboarding lands on Auth
    // (with a Skip button) so the user sees the option. If they skip
    // once or log in, we don't auto-prompt again; subsequent launches
    // go straight to Welcome. Login stays accessible from Welcome's
    // top-bar icon for anyone who skipped and later changes their mind.
    val startDest = when {
        !hasSeenOnboarding -> Routes.ONBOARDING
        !isLoggedIn && !hasSkippedAuth -> Routes.AUTH
        else -> Routes.WELCOME
    }

    NavHost(navController = navController, startDestination = startDest) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    prefs.edit().putBoolean("seen", true).apply()
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.AUTH) {
            AuthRoute(
                core = core,
                onAuthenticated = {
                    isLoggedIn = true
                    if (!navController.popBackStack(Routes.WELCOME, inclusive = false)) {
                        navController.navigate(Routes.WELCOME) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                },
                onSkip = {
                    prefs.edit().putBoolean("auth_skipped", true).apply()
                    if (!navController.popBackStack(Routes.WELCOME, inclusive = false)) {
                        navController.navigate(Routes.WELCOME) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(Routes.WELCOME) {
            WelcomeRoute(
                core = core,
                onOpenProject = { recent ->
                    navController.navigate(Routes.editor(recent.toProject()))
                },
                onOpenRecentFile = { file ->
                    val project = Project(
                        name = file.projectName,
                        root = File(file.projectRoot),
                    )
                    navController.navigate(
                        Routes.editor(project, openFile = file.relativePath)
                    )
                },
                onCreateNew = { showNewProject = true },
                onOpenExercises = { navController.navigate(Routes.EXERCISES) },
                onOpenQuestions = { navController.navigate(Routes.QUESTIONS) },
                onAbout = { navController.navigate(Routes.ABOUT) },
                onLogout = {
                    isLoggedIn = false
                },
                onLogin = {
                    navController.navigate(Routes.AUTH)
                },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("rootPath") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("openFile") { type = NavType.StringType; defaultValue = "" },
                navArgument("openChat") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val rootPath = entry.arguments?.getString("rootPath").orEmpty()
            val name = entry.arguments?.getString("name").orEmpty()
            val openFile = entry.arguments?.getString("openFile")?.takeIf { it.isNotEmpty() }
            val openChat = entry.arguments?.getBoolean("openChat") == true
            val project = remember(rootPath, name) {
                Project(name = name, root = File(rootPath))
            }
            EditorRoute(
                core = core,
                project = project,
                initialOpenFile = openFile,
                initialOpenChat = openChat,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EXERCISES) {
            ExercisesRoute(
                core = core,
                onBack = { navController.popBackStack() },
                onOpenProject = { project ->
                    navController.navigate(Routes.editor(project))
                },
            )
        }
        composable(Routes.QUESTIONS) {
            QuestionsRoute(
                core = core,
                onBack = { navController.popBackStack() },
                onLogin = { navController.navigate(Routes.AUTH) },
                onOpenConversation = { conv ->
                    // The conversation's filePath is like "category/exercise/solution.cpp".
                    // The project root is filesDir/projects/<category>.
                    val categorySlug = conv.categorySlug
                    val projectRoot = File(
                        File(core.context.filesDir, "projects"),
                        categorySlug,
                    ).canonicalFile
                    val project = Project(
                        name = categorySlug.slugToTitle(),
                        root = projectRoot,
                    )
                    scope.launch {
                        core.sessionRepository.touch(projectRoot.absolutePath, project.name)
                    }
                    val exerciseFile = "${conv.exerciseSlug}/solution.cpp"
                    navController.navigate(Routes.editor(project, openFile = exerciseFile, openChat = true))
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
                        navController.navigate(Routes.editor(project))
                    }
                }
            },
        )
    }
}

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

int main() {
    cout << "Hello, world!" << endl;
    return 0;
}
""".trimIndent()
