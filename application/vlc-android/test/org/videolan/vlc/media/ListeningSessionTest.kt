package org.videolan.vlc.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.videolan.vlc.mediadb.models.ListenedTrack
import org.videolan.vlc.mediadb.models.ListeningDay

class ListeningSessionTest {
    private val zone = ZoneId.of("America/Vancouver")
    private val wall = Instant.parse("2026-11-18T07:59:50Z")
    private val track = ListenedTrack("track", "discourse")

    @Test
    fun pauseAndBufferGapsDoNotQualifyAsListeningAndMidnightIsSplit() {
        val session = ListeningSession(track)
        session.start(0, wall, zone)
        val (days, qualified) = session.flush(30_000)
        assertEquals(listOf(
            ListeningDay(LocalDate.parse("2026-11-17").toEpochDay(), 10_000),
            ListeningDay(LocalDate.parse("2026-11-18").toEpochDay(), 20_000)
        ), days)
        assertNull(qualified)
        session.start(90_000, wall.plusSeconds(90), zone)
        session.start(100_000, wall.plusSeconds(100), zone)
        assertEquals(track, session.flush(120_000).second)
        session.start(120_000, wall.plusSeconds(120), zone)
        assertNull(session.flush(130_000, completed = true).second)
    }

    @Test
    fun shortCompletedTrackQualifiesAndAReplayIsAnIndependentSession() {
        repeat(2) {
            val session = ListeningSession(track)
            session.start(50, wall, zone)
            assertEquals(track, session.flush(1050, completed = true).second)
        }
        assertNull(ListeningSession(track).flush(1050, completed = true).second)
    }

    @Test
    fun periodsUseLocalCalendarBoundariesAndMostRecentDayWinsTies() {
        val today = LocalDate.parse("2026-11-18")
        fun day(date: String, duration: Long) = ListeningDay(LocalDate.parse(date).toEpochDay(), duration)
        val busiest = day("2026-11-18", 10)
        val totals = listeningTime(listOf(day("2025-12-31", 4), day("2026-10-31", 5),
            day("2026-11-15", 3), day("2026-11-16", 10), busiest), today)
        assertEquals(ListeningTime(10, 20, 23, 28, 32, busiest), totals)
    }
}
