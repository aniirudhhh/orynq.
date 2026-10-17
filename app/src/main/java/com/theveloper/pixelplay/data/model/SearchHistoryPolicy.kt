package com.theveloper.pixelplay.data.model

import java.util.Locale

internal object SearchHistoryPolicy {
    const val LIMIT = 20
    private val whitespace = Regex("\\s+")

    fun normalize(query: String): String = query.trim().replace(whitespace, " ")

    fun recent(items: List<SearchHistoryItem>, limit: Int = LIMIT): List<SearchHistoryItem> =
        items.sortedByDescending { it.timestamp }
            .map { it.copy(query = normalize(it.query)) }
            .filter { it.query.isNotEmpty() }
            .distinctBy { it.query.lowercase(Locale.ROOT) }
            .take(limit.coerceIn(0, LIMIT))
}
