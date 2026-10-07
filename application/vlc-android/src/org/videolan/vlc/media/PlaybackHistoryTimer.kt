package org.videolan.vlc.media

internal class PlaybackHistoryTimer {
    var track: Any? = null
        private set
    private var played = 0L
    private var startedAt: Long? = null
    private var recorded = false

    fun start(media: Any, now: Long): Long? {
        pause(now)
        if (track !== media) {
            track = media
            played = 0L
            recorded = false
        }
        if (recorded) return null
        startedAt = now
        return (60_000L - played).coerceAtLeast(0L)
    }

    fun pause(now: Long): Boolean {
        startedAt?.let { played += (now - it).coerceAtLeast(0L) }
        startedAt = null
        return played >= 60_000L && !recorded
    }

    fun reset() {
        track = null
        played = 0L
        startedAt = null
        recorded = false
    }

    fun markRecorded() {
        recorded = true
        startedAt = null
    }
}
