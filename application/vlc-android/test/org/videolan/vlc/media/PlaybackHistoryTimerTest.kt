package org.videolan.vlc.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryTimerTest {
    @Test fun countsOnlyPlayingTimeAndResetsForTheNextTrack() {
        val timer = PlaybackHistoryTimer()
        val first = Any()
        val second = Any()
        assertEquals(60_000L, timer.start(first, 0L))
        timer.pause(20_000L)
        assertEquals(40_000L, timer.start(first, 100_000L))
        assertFalse(timer.pause(139_999L))
        assertEquals(1L, timer.start(first, 200_000L))
        assertTrue(timer.pause(200_001L))
        assertEquals(0L, timer.start(first, 300_000L))
        timer.markRecorded()
        assertNull(timer.start(first, 300_000L))
        assertEquals(60_000L, timer.start(second, 300_000L))
        timer.pause(310_000L)
        timer.reset()
        assertEquals(60_000L, timer.start(second, 400_000L))
    }
}
