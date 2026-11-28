package org.videolan.vlc.gui.audio

/** Detects a silent multi-tap gesture inside a rolling time window. */
internal class DownloadAllUnlock(
    private val requiredTaps: Int = 12,
    private val windowMs: Long = 5_000L
) {
    private val taps = ArrayDeque<Long>()

    fun tap(now: Long): Boolean {
        while (taps.isNotEmpty() && now - taps.first() > windowMs) taps.removeFirst()
        taps.addLast(now)
        if (taps.size < requiredTaps) return false
        taps.clear()
        return true
    }
}
