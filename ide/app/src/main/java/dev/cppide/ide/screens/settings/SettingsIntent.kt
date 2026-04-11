package dev.cppide.ide.screens.settings

/**
 * User-driven events sent from the stateless [SettingsScreen] back to
 * [SettingsViewModel]. Keep flat — this screen is small.
 */
sealed interface SettingsIntent {
    data class Download(val modelId: String) : SettingsIntent
    data class Cancel(val modelId: String) : SettingsIntent
    data class Delete(val modelId: String) : SettingsIntent
}
