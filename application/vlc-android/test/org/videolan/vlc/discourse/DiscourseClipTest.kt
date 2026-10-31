package org.videolan.vlc.discourse

import org.junit.Assert.*
import org.junit.Test

class DiscourseClipTest {
    @Test fun initialRangeStartsAtPlaybackAndClampsAtEnd() {
        assertEquals(ClipRange(12_345, 72_345), ClipRange.initial(12_345, 100_000))
        assertEquals(ClipRange(90_000, 100_000), ClipRange.initial(90_000, 100_000))
        assertEquals(ClipRange(99_000, 100_000), ClipRange.initial(100_000, 100_000))
        assertEquals(ClipRange(0, 1_000), ClipRange.initial(0, 1_000))
    }

    @Test fun rangesRejectCrossedShortAndOutOfBoundsSelections() {
        assertTrue(ClipRange(0, 1_000).isValid(1_000))
        listOf(ClipRange(-1, 1_000), ClipRange(0, 999), ClipRange(2_000, 1_000), ClipRange(0, 2_001))
            .forEach { assertFalse(it.isValid(2_000)) }
    }

    @Test fun timestampsSupportLongDiscoursesAndRejectInvalidInput() {
        assertEquals("1:02:03", clipTimestamp(3_723_000))
        assertEquals(3_723_000L, parseClipTimestamp("1:02:03"))
        assertEquals(90_000L, parseClipTimestamp("1:30"))
        listOf("-1:00", "1:60", "1::02", "oops", "1", "999999999999:00").forEach { assertNull(parseClipTimestamp(it)) }
    }

    @Test fun mp3PrerollSuppliesOnlyRequiredReservoirBytes() {
        fun frame(reservoir: Int) = ByteArray(417).apply {
            this[0] = 0xFF.toByte(); this[1] = 0xFB.toByte(); this[2] = 0x90.toByte()
            this[4] = (reservoir shr 1).toByte(); this[5] = ((reservoir and 1) shl 7).toByte()
        }
        val history = List(4) { frame(0) }
        assertTrue(mp3Preroll(history, frame(0)).isEmpty())
        assertEquals(1, mp3Preroll(history, frame(100)).size)
        assertEquals(2, mp3Preroll(history, frame(511)).size)
        assertEquals(0, mp3Preroll(emptyList(), frame(511)).size)
    }
}
