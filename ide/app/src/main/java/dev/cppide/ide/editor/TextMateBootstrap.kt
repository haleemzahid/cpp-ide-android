package dev.cppide.ide.editor

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time setup for sora-editor's TextMate stack:
 *  1. Tell its [FileProviderRegistry] how to resolve `assets/...` paths.
 *  2. Load the language index (`textmate/languages.json`) which registers
 *     every grammar file we ship.
 *  3. Load and activate the Dark+ theme so the editor renders with our
 *     VSCode-style colors out of the box.
 *
 * Idempotent — safe to call from [dev.cppide.ide.CppIdeApp.onCreate]
 * even if some part already ran.
 */
object TextMateBootstrap {

    private val initialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        FileProviderRegistry.getInstance()
            .addFileProvider(AssetsFileResolver(context.applicationContext.assets))

        GrammarRegistry.getInstance().loadGrammars(LANGUAGES_INDEX)

        loadTheme(DARK_THEME_PATH, isDark = true)
        loadTheme(LIGHT_THEME_PATH, isDark = false)

        // Default to dark; EditorPane flips this live based on the system
        // theme via [ThemeRegistry.setTheme].
        ThemeRegistry.getInstance().setTheme(DARK_THEME_NAME)
    }

    /** Name of the theme as it was registered — use with [ThemeRegistry.setTheme]. */
    const val DARK_THEME_NAME = "Dark+"
    const val LIGHT_THEME_NAME = "Light+"

    private fun loadTheme(path: String, isDark: Boolean) {
        val stream = FileProviderRegistry.getInstance()
            .tryGetInputStream(path)
            ?: error("TextMate theme not found at $path")
        val source = IThemeSource.fromInputStream(stream, path, null)
        val model = ThemeModel(source).apply { this.isDark = isDark }
        ThemeRegistry.getInstance().loadTheme(model)
    }

    private const val LANGUAGES_INDEX = "textmate/languages.json"
    private const val DARK_THEME_PATH = "textmate/themes/dark_plus_merged.json"
    private const val LIGHT_THEME_PATH = "textmate/themes/light_plus.json"
}
