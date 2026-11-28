package org.videolan.vlc.media

internal const val MAX_RECENT_PLAYED = 16

/** Alternates the newest entries from each source, preserving each source's order. */
internal fun <T> interleaveRecent(
    discourse: List<T>,
    local: List<T>,
    limit: Int = MAX_RECENT_PLAYED
): List<T> {
    if (limit <= 0) return emptyList()
    val result = ArrayList<T>(minOf(limit, discourse.size + local.size))
    var index = 0
    while (result.size < limit && (index < discourse.size || index < local.size)) {
        if (index < discourse.size) result += discourse[index]
        if (result.size < limit && index < local.size) result += local[index]
        index++
    }
    return result
}

/** Splits newest-first history into the entries to keep and the stale entries to remove. */
internal fun <T> splitRecentHistory(items: List<T>, limit: Int = MAX_RECENT_PLAYED): Pair<List<T>, List<T>> =
    items.take(limit.coerceAtLeast(0)) to items.drop(limit.coerceAtLeast(0))
