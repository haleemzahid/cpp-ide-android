package dev.cppide.ide.screens.editor

/**
 * One-shot events from the editor ViewModel that can't be represented
 * as idempotent state (which would re-fire on recomposition). Collected
 * by the screen via a SharedFlow.
 */
sealed interface EditorEvent {
    /** Ask the host activity to launch a system share sheet with [content]. */
    data class ShareFile(val fileName: String, val content: String) : EditorEvent
}
