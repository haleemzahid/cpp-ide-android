package dev.cppide.ide.screens.editor

import dev.cppide.core.build.Diagnostic

/**
 * User intents the editor screen forwards to its ViewModel. Sealed so the
 * UI side can't accidentally invent new actions; pattern-matching is
 * exhaustive.
 */
sealed interface EditorIntent {
    // file system
    data object ToggleDrawer : EditorIntent
    data object CloseDrawer : EditorIntent
    data class OpenFile(val relativePath: String) : EditorIntent
    data class EditContent(val newContent: String) : EditorIntent
    data object Save : EditorIntent

    // build / run
    data object RunOrStop : EditorIntent
    data object ToggleBottomPanel : EditorIntent
    data class SwitchBottomTab(val tab: BottomPanelTab) : EditorIntent
    data class JumpToDiagnostic(val diagnostic: Diagnostic) : EditorIntent
    data object ClearTerminal : EditorIntent

    // misc
    data object DismissError : EditorIntent
}
