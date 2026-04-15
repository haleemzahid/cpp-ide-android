package dev.cppide.ide.screens.editor

import dev.cppide.core.build.Diagnostic
import dev.cppide.core.debug.SourceBreakpoint

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
    data class CloseTab(val index: Int) : EditorIntent
    data class SelectTab(val index: Int) : EditorIntent
    data class EditContent(val newContent: String) : EditorIntent
    data object Save : EditorIntent
    data object ShareActiveFile : EditorIntent
    /** Flip between rendered preview and raw source for the active .md file. */
    data object ToggleMarkdownPreview : EditorIntent
    /** Create a new file under [parentRelativePath] (empty = project root). */
    data class CreateFile(val parentRelativePath: String, val name: String) : EditorIntent
    /** Create a new directory under [parentRelativePath] (empty = project root). */
    data class CreateDirectory(val parentRelativePath: String, val name: String) : EditorIntent
    /** Delete a file at [relativePath]. */
    data class DeleteFile(val relativePath: String) : EditorIntent
    /** Rename a file from [relativePath] to a sibling with [newName]. */
    data class RenameFile(val relativePath: String, val newName: String) : EditorIntent

    // build / run
    data object RunOrStop : EditorIntent
    data object ToggleBottomPanel : EditorIntent
    data class SwitchBottomTab(val tab: BottomPanelTab) : EditorIntent
    data class JumpToDiagnostic(val diagnostic: Diagnostic) : EditorIntent
    data object ClearTerminal : EditorIntent

    // debug
    data object StartDebug : EditorIntent
    /** Step over the current source line (F10 in VSCode). */
    data object DebugStepOver : EditorIntent
    /** Step into the function call on the current line (F11). */
    data object DebugStepInto : EditorIntent
    /** Step out of the current function (Shift+F11). */
    data object DebugStepOut : EditorIntent
    /** Legacy "Step" intent — kept as alias for the existing DebugPanel button.
     *  New UI uses the three explicit step intents above. */
    data object DebugStep : EditorIntent
    data object DebugContinue : EditorIntent
    data object DebugPause : EditorIntent
    data object DebugStop : EditorIntent
    data class ToggleBreakpoint(val line: Int) : EditorIntent
    /** Remove a specific breakpoint by (file, line) regardless of
     *  currently-open file. Used by the Debug panel's breakpoint list. */
    data class RemoveBreakpoint(val breakpoint: SourceBreakpoint) : EditorIntent
    /** Expand or collapse a variable in the Variables panel. The
     *  reference is the DAP variablesReference handle for the entry. */
    data class ToggleVariableExpansion(val variablesReference: Int) : EditorIntent

    // chat
    data class UpdateChatInput(val text: String) : EditorIntent
    data object SendChatMessage : EditorIntent

    // misc
    data object DismissError : EditorIntent
}
