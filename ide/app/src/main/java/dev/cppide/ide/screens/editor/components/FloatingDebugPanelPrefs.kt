package dev.cppide.ide.screens.editor.components

import android.content.Context

/**
 * Device-wide (not per-project) persistence for the floating debug
 * toolbar's drag offset. See [FloatingDebugPanel] for the coordinate
 * meaning of dx / dy.
 */
object FloatingDebugPanelPrefs {
    private const val PREFS_NAME = "floating_debug_panel"
    private const val KEY_DX = "dx"
    private const val KEY_DY = "dy"

    data class Position(val dx: Float, val dy: Float)

    fun load(context: Context): Position {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Position(
            dx = p.getFloat(KEY_DX, 0f),
            dy = p.getFloat(KEY_DY, 0f),
        )
    }

    fun save(context: Context, position: Position) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_DX, position.dx)
            .putFloat(KEY_DY, position.dy)
            .apply()
    }
}
