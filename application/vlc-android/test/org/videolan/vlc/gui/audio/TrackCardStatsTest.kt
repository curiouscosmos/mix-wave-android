package org.videolan.vlc.gui.audio

import android.app.Application
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
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
    @Test fun statsIncludeLikesAndAccessibleDownloadStatus() {
        val view = TextView(RuntimeEnvironment.application)
        view.bindTrackStats(TrackCardStats(5, 40, true))
        assertEquals("5 plays | 40 likes | \uFFFC", view.text.toString())
        assertEquals(1, (view.text as Spanned).getSpans(0, view.text.length, ImageSpan::class.java).size)
        assertTrue(view.contentDescription.contains(view.context.getString(R.string.discourse_downloaded)))
    }

    @Test fun zeroAndOneLikeStayHiddenAndSingularPlayIsFormatted() {
        val view = TextView(RuntimeEnvironment.application)
        listOf(0, 1).forEach { likes ->
            view.bindTrackStats(TrackCardStats(1, likes, false))
            assertEquals("1 play | \uFFFC", view.text.toString())
        }
        assertTrue(view.contentDescription.contains(view.context.getString(R.string.not_downloaded)))
    }
}
