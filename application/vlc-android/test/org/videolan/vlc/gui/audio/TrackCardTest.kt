package org.videolan.vlc.gui.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.videolan.vlc.viewmodels.HomeTrack

class TrackCardTest {
    @Test fun historySnapshotsDetectPlaybackProgressWithoutMetadataChanges() {
        val before = HomeTrack("track", "Title", "Album", null, playbackTime = 1000)
        assertEquals(before, before.copy())
        assertNotEquals(before, before.copy(playbackTime = 2000))
    }

    @Test fun listeningProgressHandlesMissingAndStaleDurations() {
        assertEquals(50, trackListeningProgress(50, 100))
        assertEquals(0, trackListeningProgress(50, 0))
        assertEquals(0, trackListeningProgress(50, -1))
        assertEquals(0, trackListeningProgress(-1, 100))
        assertEquals(100, trackListeningProgress(150, 100))
        assertEquals(100, trackListeningProgress(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
