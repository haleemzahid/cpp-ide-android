package dev.cppide.ide

import android.app.Application
import dev.cppide.core.Core
import dev.cppide.ide.editor.TextMateBootstrap

/**
 * Application class — owns the singleton [Core]. Activities reach it via
 * `(application as CppIdeApp).core`.
 */
class CppIdeApp : Application() {

    lateinit var core: Core
        private set

    override fun onCreate() {
        super.onCreate()
        core = Core.create(this)
        // Load TextMate grammars + theme exactly once per process so any
        // editor view created later has syntax highlighting available.
        TextMateBootstrap.init(this)
    }
}
