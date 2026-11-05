package org.videolan.vlc.discourse

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipVideoGeometryTest {
    @Test fun canvasFollowsDisplayedOrientation() {
        assertEquals(1280 to 720, clipVideoCanvas(1920, 1080))
        assertEquals(720 to 1280, clipVideoCanvas(1080, 1920))
        assertEquals(720 to 720, clipVideoCanvas(3000, 3000))
        assertEquals(1280 to 720, clipVideoCanvas(4000, 1000))
    }
}
