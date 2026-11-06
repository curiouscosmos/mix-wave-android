package org.videolan.vlc.discourse

import java.util.Locale

/** Clamp cues to the exported range and rebase them so playback starts at 00:00:00,000. */
internal fun clipCaptions(cues: List<CaptionCue>, range: ClipRange): List<CaptionCue> = cues.mapNotNull { cue ->
    val start = maxOf(cue.startMs, range.startMs)
    val end = minOf(cue.endMs, range.endMs)
    if (end <= start) null else CaptionCue(start - range.startMs, end - range.startMs, cue.text)
}

internal fun formatSrt(cues: List<CaptionCue>): String = buildString {
    cues.forEachIndexed { index, cue ->
        if (index > 0) append('\n')
        append(index + 1).append('\n')
        append(srtTimestamp(cue.startMs)).append(" --> ").append(srtTimestamp(cue.endMs)).append('\n')
        append(cue.text).append('\n')
    }
}

internal fun srtTimestamp(timeMs: Long): String {
    val value = timeMs.coerceAtLeast(0)
    val hours = value / 3_600_000
    val minutes = value / 60_000 % 60
    val seconds = value / 1_000 % 60
    val millis = value % 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
}
