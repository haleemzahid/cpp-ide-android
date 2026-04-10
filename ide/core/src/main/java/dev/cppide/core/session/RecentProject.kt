package dev.cppide.core.session

/**
 * A user-visible "recently opened" project entry. Persisted via Room so it
 * survives app restarts. Keyed by the absolute [rootPath] so opening the
 * same folder twice updates [lastOpenedAt] rather than creating a duplicate.
 */
data class RecentProject(
    val rootPath: String,
    val displayName: String,
    val lastOpenedAt: Long,
    val pinned: Boolean = false,
)
