package org.videolan.vlc.discourse

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipVideoGeometryTest {
    @Test fun endCardFadesForOneSecondThenHolds() {
        assertEquals(0f, clipEndCardOpacity(4_000_000, 10_000_000), 0f)
        assertEquals(0f, clipEndCardOpacity(5_000_000, 10_000_000), 0f)
        assertEquals(0.5f, clipEndCardOpacity(5_500_000, 10_000_000), 0f)
        assertEquals(1f, clipEndCardOpacity(6_000_000, 10_000_000), 0f)
        assertEquals(1f, clipEndCardOpacity(9_900_000, 10_000_000), 0f)
    }

    @Test fun shortClipsStartFadingImmediately() {
        assertEquals(0f, clipEndCardOpacity(0, 2_000_000), 0f)
        assertEquals(0.5f, clipEndCardOpacity(500_000, 2_000_000), 0f)
        assertEquals(1f, clipEndCardOpacity(1_000_000, 2_000_000), 0f)
        assertEquals(0.5f, clipEndCardOpacity(250_000, 500_000), 0f)
        assertEquals(1f, clipEndCardOpacity(500_000, 500_000), 0f)
    }

    @Test fun canvasFollowsDisplayedOrientation() {
        assertEquals(1280 to 720, clipVideoCanvas(1920, 1080))
        assertEquals(720 to 1280, clipVideoCanvas(1080, 1920))
        assertEquals(720 to 720, clipVideoCanvas(3000, 3000))
        assertEquals(1280 to 720, clipVideoCanvas(4000, 1000))
    }
}
