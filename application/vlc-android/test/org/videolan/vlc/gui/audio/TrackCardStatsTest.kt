package org.videolan.vlc.gui.audio

import android.app.Application
import android.text.Spanned
import android.widget.TextView
import android.widget.ProgressBar
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.videolan.vlc.R

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28], qualifiers = "en")
class TrackCardStatsTest {
    @Test fun progressAppearsAtOnePercentAndHidesWhenReboundToZero() {
        val bar = ProgressBar(RuntimeEnvironment.application, null, android.R.attr.progressBarStyleHorizontal)
        bar.bindTrackProgress(trackListeningProgress(9, 1000))
        assertEquals(View.GONE, bar.visibility)
        bar.bindTrackProgress(trackListeningProgress(10, 1000))
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(1, bar.progress)
        bar.bindTrackProgress(0)
        assertEquals(View.GONE, bar.visibility)
    }

    @Test fun statsIncludeLikesAndAccessibleDownloadStatus() {
        val view = TextView(RuntimeEnvironment.application)
        view.bindTrackStats(TrackCardStats(5, 40, true))
        assertEquals("5 plays  40 likes  \uFFFC", view.text.toString())
        assertEquals(3, (view.text as Spanned).getSpans(0, view.text.length, TrackBadgeSpan::class.java).size)
        assertTrue(view.contentDescription.contains(view.context.getString(R.string.discourse_downloaded)))
    }

    @Test fun zeroCountsAreHiddenAndOneLikeIsShown() {
        val view = TextView(RuntimeEnvironment.application)
        view.bindTrackStats(TrackCardStats(0, 0, false))
        assertEquals("\uFFFC", view.text.toString())
        view.bindTrackStats(TrackCardStats(0, 1, false))
        assertEquals("1 like  \uFFFC", view.text.toString())
        assertTrue(view.contentDescription.contains(view.context.getString(R.string.not_downloaded)))
    }
}
