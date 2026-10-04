package org.videolan.vlc.discourse

import org.junit.Assert.*
import org.junit.Test

class DiscourseMetadataTest {
    private fun audio(duration: Double?, size: Long?) = DiscourseAudio(
        "track", "discourse", "Title", null, "hindi", "Track", "https://example.test/audio.mp3",
        duration, size, null, 1, "", ""
    )

    @Test fun completePartialAndMissingTotals() {
        assertEquals(DiscourseMetadataTotals(180.0, true, 300L, true),
            discourseMetadataTotals(listOf(audio(60.0, 100), audio(120.0, 200))))
        assertEquals(DiscourseMetadataTotals(60.0, false, 200L, false),
            discourseMetadataTotals(listOf(audio(60.0, null), audio(null, 200))))
        assertEquals(DiscourseMetadataTotals(null, false, null, false),
            discourseMetadataTotals(listOf(audio(null, null), audio(0.0, 0), audio(Double.NaN, -1))))
        assertEquals(DiscourseMetadataTotals(null, false, null, false), discourseMetadataTotals(emptyList()))
    }
}
