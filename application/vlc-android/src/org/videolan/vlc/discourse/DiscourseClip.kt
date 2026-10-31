package org.videolan.vlc.discourse

import java.util.Locale

/** Times are source timestamps, independent of playback speed and audio effects. */
data class ClipRange(val startMs: Long, val endMs: Long) {
    fun isValid(durationMs: Long) = startMs >= 0 && endMs <= durationMs && endMs - startMs >= 1_000

    companion object {
        fun initial(positionMs: Long, durationMs: Long): ClipRange {
            require(durationMs >= 1_000)
            val start = positionMs.coerceIn(0, durationMs - 1_000)
            return ClipRange(start, (start + 60_000).coerceAtMost(durationMs))
        }
    }
}

internal fun clipTimestamp(timeMs: Long): String {
    val seconds = timeMs / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

internal fun parseClipTimestamp(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size !in 2..3) return null
    val numbers = parts.map { part ->
        if (part.isEmpty() || part.length > 6 || part.any { !it.isDigit() }) return null
        part.toLongOrNull() ?: return null
    }
    if (numbers.drop(1).any { it > 59 }) return null
    return numbers.fold(0L) { total, value -> total * 60 + value } * 1_000
}
