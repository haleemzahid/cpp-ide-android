package dev.cppide.ide.screens.editor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-project UI state: which tabs are open and which one is active.
 *
 * Persisted in a single SharedPreferences file keyed by project root
 * path, so closing and reopening a project restores the exact tab
 * layout the user left. Intentionally separate from [core.session] —
 * this is pure UI state, not something another service needs.
 */
data class ProjectUiState(
    val openPaths: List<String>,
    val activePath: String?,
) {
    companion object {
        private const val PREFS_NAME = "project_ui_state"
        private const val KEY_TABS = "tabs"
        private const val KEY_ACTIVE = "active"

        fun load(context: Context, projectRoot: File): ProjectUiState {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = projectRoot.absolutePath
            val raw = prefs.getString(key, null) ?: return ProjectUiState(emptyList(), null)
            return runCatching {
                val obj = JSONObject(raw)
                val arr = obj.optJSONArray(KEY_TABS) ?: JSONArray()
                val tabs = buildList {
                    for (i in 0 until arr.length()) add(arr.getString(i))
                }
                ProjectUiState(
                    openPaths = tabs,
                    activePath = obj.optString(KEY_ACTIVE, "").takeIf { it.isNotEmpty() },
                )
            }.getOrElse { ProjectUiState(emptyList(), null) }
        }

        fun save(context: Context, projectRoot: File, state: ProjectUiState) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val obj = JSONObject().apply {
                put(KEY_TABS, JSONArray(state.openPaths))
                state.activePath?.let { put(KEY_ACTIVE, it) }
            }
            prefs.edit().putString(projectRoot.absolutePath, obj.toString()).apply()
        }
    }
}
