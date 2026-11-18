package org.videolan.vlc.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.videolan.vlc.mediadb.models.ListenedTrack
import org.videolan.vlc.mediadb.models.ListeningDay

internal class ListeningSession(val track: ListenedTrack) {
    private var elapsed = 0L
    private var qualified = false
    private var segment: Segment? = null

    private data class Segment(val elapsedAt: Long, val wallAt: Instant, val zone: ZoneId)

    fun start(now: Long, wall: Instant, zone: ZoneId) {
        if (segment == null) segment = Segment(now, wall, zone)
    }

    fun flush(now: Long, completed: Boolean = false): Pair<List<ListeningDay>, ListenedTrack?> {
        val active = segment
        segment = null
        val duration = active?.let { (now - it.elapsedAt).coerceAtLeast(0) } ?: 0
        elapsed += duration
        val newlyQualified = !qualified && (elapsed >= 60_000 || completed && elapsed > 0)
        if (newlyQualified) qualified = true
        val days = if (active == null || duration == 0L) emptyList()
            else splitListeningDays(active.wallAt, duration, active.zone)
        return days to track.takeIf { newlyQualified }
    }
}

internal fun splitListeningDays(start: Instant, duration: Long, zone: ZoneId): List<ListeningDay> = buildList {
    val end = start.plusMillis(duration)
    var cursor = start
    while (cursor < end) {
        val day = cursor.atZone(zone).toLocalDate()
        val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant()
        val boundary = minOf(end, nextMidnight)
        add(ListeningDay(day.toEpochDay(), boundary.toEpochMilli() - cursor.toEpochMilli()))
        cursor = boundary
    }
}

internal data class ListeningTime(
    val today: Long, val week: Long, val month: Long, val year: Long, val overall: Long,
    val busiestDay: ListeningDay?
)

internal fun listeningTime(days: List<ListeningDay>, today: LocalDate): ListeningTime {
    val end = today.toEpochDay()
    val week = end - today.dayOfWeek.ordinal
    val month = today.withDayOfMonth(1).toEpochDay()
    val year = today.withDayOfYear(1).toEpochDay()
    var todayMs = 0L
    var weekMs = 0L
    var monthMs = 0L
    var yearMs = 0L
    var overallMs = 0L
    var busiest: ListeningDay? = null
    for (day in days) {
        val duration = day.durationMs
        overallMs += duration
        if (day.day == end) todayMs += duration
        if (day.day in week..end) weekMs += duration
        if (day.day in month..end) monthMs += duration
        if (day.day in year..end) yearMs += duration
        val previous = busiest
        if (previous == null || duration > previous.durationMs ||
            (duration == previous.durationMs && day.day > previous.day)) busiest = day
    }
    return ListeningTime(todayMs, weekMs, monthMs, yearMs, overallMs, busiest)
}
