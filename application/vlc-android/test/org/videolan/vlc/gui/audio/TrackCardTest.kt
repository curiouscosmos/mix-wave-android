package org.videolan.vlc.gui.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackCardTest {
    @Test fun listeningProgressHandlesMissingAndStaleDurations() {
        assertEquals(50, trackListeningProgress(50, 100))
        assertEquals(0, trackListeningProgress(50, 0))
        assertEquals(0, trackListeningProgress(50, -1))
        assertEquals(0, trackListeningProgress(-1, 100))
        assertEquals(100, trackListeningProgress(150, 100))
        assertEquals(100, trackListeningProgress(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
