package org.videolan.vlc.gui.audio

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.videolan.vlc.mediadb.models.ListeningDay

class ListeningStatsTrendTest {
    private val today = LocalDate.of(2026, 11, 18)

    @Test
    fun weekChartAndHeatmapShowGapsAndLeaveFutureCellsBlank() {
        val trend = listeningStatsTrend(listOf(day(0, 120), day(1, 60), day(3, 60)), today)
        assertEquals(listOf(0L, 0L, 0L, 60L, 0L, 60L, 120L), trend.lastWeek)
        assertEquals(60L, trend.dailyAverage)
        assertEquals(2, trend.streak)
        assertEquals(listOf(120L, 180L, 240L, 240L, 240L), trend.periodPeaks)
        assertEquals(listOf(0L, 60L, 120L, null, null, null, null), trend.activity.takeLast(7))
    }

    @Test
    fun streakStaysCurrentThroughTodayUntilTheNextDayWithoutListening() {
        val days = listOf(day(1, 60), day(2, 60))
        assertEquals(2, listeningStatsTrend(days, today).streak)
        assertEquals(0, listeningStatsTrend(days, today.plusDays(1)).streak)
        val empty = listeningStatsTrend(emptyList(), today)
        assertEquals(0L, empty.dailyAverage)
        assertNull(empty.time.busiestDay)
    }

    private fun day(ago: Long, duration: Long) = ListeningDay(today.minusDays(ago).toEpochDay(), duration)
}
