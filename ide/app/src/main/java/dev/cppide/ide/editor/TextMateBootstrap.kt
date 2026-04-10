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

        val themeStream = FileProviderRegistry.getInstance()
            .tryGetInputStream(THEME_PATH)
            ?: error("TextMate theme not found at $THEME_PATH")

        val themeSource = IThemeSource.fromInputStream(themeStream, THEME_PATH, null)
        val themeModel = ThemeModel(themeSource).apply { isDark = true }
        ThemeRegistry.getInstance().loadTheme(themeModel)
        ThemeRegistry.getInstance().setTheme("Dark+")
    }

    private const val LANGUAGES_INDEX = "textmate/languages.json"
    private const val THEME_PATH = "textmate/themes/dark_plus_merged.json"
}
