package org.videolan.vlc.discourse

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipCaptionsTest {
    @Test fun clampsAndRebasesCuesToClipStart() {
        val range = ClipRange(10_000, 20_000)
        val cues = listOf(
            CaptionCue(0, 5_000, "before"),
            CaptionCue(9_000, 12_000, "overlap start"),
            CaptionCue(15_000, 18_000, "inside"),
            CaptionCue(19_000, 25_000, "overlap end"),
            CaptionCue(20_000, 22_000, "after")
        )
        assertEquals(listOf(
            CaptionCue(0, 2_000, "overlap start"),
            CaptionCue(5_000, 8_000, "inside"),
            CaptionCue(9_000, 10_000, "overlap end")
        ), clipCaptions(cues, range))
    }

    @Test fun formatsSrtWithRelativeTimestamps() {
        val srt = formatSrt(listOf(CaptionCue(0, 1_234, "Hello"), CaptionCue(3_724_000, 3_725_000, "Later")))
        assertEquals("1\n00:00:00,000 --> 00:00:01,234\nHello\n\n2\n01:02:04,000 --> 01:02:05,000\nLater\n", srt)
    }

    @Test fun exportOptionsRoundTripCaptionPath() {
        val options = ClipExportOptions(captionPath = "/captions.srt")
        assertEquals(options, ClipExportOptions.fromJson(options.toJson()))
    }
}
