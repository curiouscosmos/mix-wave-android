package org.videolan.vlc.gui

import android.app.Application
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.vlc.R

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class ScrollingBottomPaddingTest {
    @Test
    fun listsKeepTheirViewportAndReceiveClearanceWithoutAccumulatingIt() {
        val activity = Robolectric.buildActivity(AudioPlayerContainerActivity::class.java).get()
        val content = FrameLayout(activity)
        val tracks = RecyclerView(activity).apply { setPadding(3, 5, 7, 9) }
        val playlists = RecyclerView(activity)
        content.addView(tracks)
        content.addView(playlists)

        activity.applyScrollingBottomPadding(content, 180)
        activity.applyScrollingBottomPadding(content, 180)

        assertEquals(0, content.paddingBottom)
        assertEquals(189, tracks.paddingBottom)
        assertEquals(180, playlists.paddingBottom)
        assertEquals(3, tracks.paddingLeft)
        assertEquals(5, tracks.paddingTop)
        assertEquals(7, tracks.paddingRight)
        assertFalse(tracks.clipToPadding)

        activity.applyScrollingBottomPadding(content, 108)
        assertEquals(117, tracks.paddingBottom)
        assertEquals(108, playlists.paddingBottom)

        // Switching video grid/list modes replaces the list's own padding.
        tracks.setPadding(4, 4, 4, 4)
        activity.applyScrollingBottomPadding(content, 108)
        assertEquals(112, tracks.paddingBottom)
    }

    @Test
    fun outerScrollViewOwnsClearanceInsteadOfPaddingNestedListsTwice() {
        val activity = Robolectric.buildActivity(AudioPlayerContainerActivity::class.java).get()
        val outer = NestedScrollView(activity).apply { setPadding(0, 0, 0, 12) }
        val list = RecyclerView(activity).apply { setPadding(0, 0, 0, 6) }
        outer.addView(list)

        activity.applyScrollingBottomPadding(outer, 180)

        assertEquals(192, outer.paddingBottom)
        assertEquals(6, list.paddingBottom)
        assertFalse(outer.clipToPadding)
    }

    @Test
    fun mixerKeepsFixedControlsAbovePlayerWithoutDoublePaddingItsList() {
        val activity = Robolectric.buildActivity(AudioPlayerContainerActivity::class.java).get()
        val content = LinearLayout(activity)
        val list = RecyclerView(activity)
        val controls = View(activity).apply { id = R.id.audio_mixer_controls }
        content.addView(list)
        content.addView(controls, LinearLayout.LayoutParams(100, 50).apply { bottomMargin = 28 })

        activity.applyScrollingBottomPadding(content, 180)
        activity.applyScrollingBottomPadding(content, 108)

        assertEquals(136, (controls.layoutParams as LinearLayout.LayoutParams).bottomMargin)
        assertEquals(0, list.paddingBottom)
        assertEquals(0, content.paddingBottom)
    }
}
