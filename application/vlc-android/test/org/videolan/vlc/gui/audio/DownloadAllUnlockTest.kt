package org.videolan.vlc.gui.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAllUnlockTest {
    @Test
    fun twelveTapsInsideTheWindowUnlock() {
        val unlock = DownloadAllUnlock()
        repeat(11) { index -> assertFalse(unlock.tap(index * 100L)) }
        assertTrue(unlock.tap(1_200L))
    }

    @Test
    fun tapsOutsideTheWindowExpire() {
        val unlock = DownloadAllUnlock()
        repeat(11) { index -> assertFalse(unlock.tap(index * 100L)) }
        assertFalse(unlock.tap(7_000L))
        repeat(10) { index -> assertFalse(unlock.tap(7_100L + index * 100L)) }
        assertTrue(unlock.tap(8_200L))
    }

    @Test
    fun slowTapsDoNotAccumulate() {
        val unlock = DownloadAllUnlock()
        repeat(20) { index ->
            assertFalse(unlock.tap(index * 6_000L))
        }
    }
}
