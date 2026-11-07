package org.videolan.vlc.discourse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
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
        assertEquals(1280 to 1280, clipVideoCanvas(3000, 3000))
        assertEquals(1280 to 320, clipVideoCanvas(4000, 1000))
        assertEquals(320 to 1280, clipVideoCanvas(1000, 4000))
        assertEquals(1280 to 960, clipVideoCanvas(640, 480))
        val (width, height) = clipVideoCanvas(1921, 1081)
        assertTrue(width <= 1280 && height <= 1280)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
        assertEquals(1921.0 / 1081, width.toDouble() / height, 0.005)
    }

    @Test fun selectedRatiosOverrideSourceOrientation() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 1000 to 1000)) {
            assertEquals(720 to 1280, clipVideoCanvas(width, height, ClipVideoRatio.PORTRAIT))
            assertEquals(1280 to 720, clipVideoCanvas(width, height, ClipVideoRatio.LANDSCAPE))
        }
    }

    @Test fun visualFillsCanvasWithSymmetricCentreCrop() {
        assertArrayEquals(intArrayOf(-280, 0, 1280, 1280), clipVideoViewport(720, 1280, 1000, 1000))
        assertArrayEquals(intArrayOf(0, -280, 1280, 1280), clipVideoViewport(1280, 720, 1000, 1000))
        assertArrayEquals(intArrayOf(0, 0, 1280, 720), clipVideoViewport(1280, 720, 1920, 1080))
        for ((sourceWidth, sourceHeight) in listOf(1920 to 1080, 1080 to 1920, 4000 to 1000)) {
            val viewport = clipVideoViewport(720, 1280, sourceWidth, sourceHeight)
            assertTrue(viewport[2] >= 720 && viewport[3] >= 1280)
            assertEquals(sourceWidth.toDouble() / sourceHeight, viewport[2].toDouble() / viewport[3], 0.005)
        }
    }
}
