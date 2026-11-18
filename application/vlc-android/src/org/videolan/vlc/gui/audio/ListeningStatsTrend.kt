package org.videolan.vlc.gui.audio

import java.time.LocalDate
import org.videolan.vlc.media.ListeningTime
import org.videolan.vlc.media.listeningTime
import org.videolan.vlc.mediadb.models.ListeningDay

internal data class ListeningStatsTrend(
    val today: LocalDate,
    val time: ListeningTime,
    val periodPeaks: List<Long>,
    val lastWeek: List<Long>,
    val activity: List<Long?>,
    val dailyAverage: Long,
    val streak: Int
)

internal fun listeningStatsTrend(days: List<ListeningDay>, today: LocalDate): ListeningStatsTrend {
    val date = today.toEpochDay()
    val byDay = days.associate { it.day to it.durationMs }
    val time = listeningTime(days, today)
    val weeks = mutableMapOf<Long, Long>()
    val months = mutableMapOf<Long, Long>()
    val years = mutableMapOf<Int, Long>()
    for (day in days) {
        if (day.day > date) continue
        val local = LocalDate.ofEpochDay(day.day)
        weeks.merge(day.day - local.dayOfWeek.ordinal, day.durationMs, Long::plus)
        months.merge(day.day - local.dayOfMonth + 1, day.durationMs, Long::plus)
        years.merge(local.year, day.durationMs, Long::plus)
    }
    val peaks = listOf(time.busiestDay?.durationMs ?: 0, weeks.values.maxOrNull() ?: 0,
        months.values.maxOrNull() ?: 0, years.values.maxOrNull() ?: 0, time.overall)
    val first = days.minOfOrNull { it.day } ?: date
    val average = time.overall / (date - first + 1).coerceAtLeast(1)
    var streakDay = if ((byDay[date] ?: 0) > 0) date else date - 1
    var streak = 0
    while ((byDay[streakDay] ?: 0) > 0) {
        streak++
        streakDay--
    }
    val firstMonday = date - today.dayOfWeek.ordinal - (13 * 7)
    return ListeningStatsTrend(today, time, peaks, (date - 6..date).map { byDay[it] ?: 0 },
        List(14 * 7) { index -> (firstMonday + index).takeIf { it <= date }?.let { byDay[it] ?: 0 } },
        average, streak)
}
