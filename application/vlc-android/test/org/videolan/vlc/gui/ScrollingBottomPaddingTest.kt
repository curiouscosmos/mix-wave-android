package org.videolan.vlc.gui

import android.app.Application
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
    fun homePagerKeepsFullHeightAndGivesClearanceToItsContentLists() {
        val activity = Robolectric.buildActivity(AudioPlayerContainerActivity::class.java).get()
        val pager = ViewPager2(activity)
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 3
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val page = FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    addView(RecyclerView(activity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                return object : RecyclerView.ViewHolder(page) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        fun layoutPager() {
            pager.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            pager.layout(0, 0, 360, 640)
        }
        layoutPager()
        val internalPager = pager.getChildAt(0) as RecyclerView
        val originalClipping = internalPager.clipToPadding
        activity.applyScrollingBottomPadding(pager, 180)
        activity.applyScrollingBottomPadding(pager, 180)
        layoutPager()

        assertEquals(0, internalPager.paddingBottom)
        assertEquals(originalClipping, internalPager.clipToPadding)
        val firstPage = internalPager.findViewHolderForAdapterPosition(0)!!.itemView as FrameLayout
        assertEquals(pager.height, firstPage.height)
        assertEquals(180, firstPage.getChildAt(0).paddingBottom)

        pager.setCurrentItem(2, false)
        layoutPager()
        // The fragment-view callback reapplies clearance when a new Home page attaches.
        activity.applyScrollingBottomPadding(pager, 180)
        layoutPager()
        val newPage = internalPager.findViewHolderForAdapterPosition(2)!!.itemView as FrameLayout
        assertEquals(pager.height, newPage.height)
        assertEquals(180, newPage.getChildAt(0).paddingBottom)
        assertFalse((newPage.getChildAt(0) as RecyclerView).clipToPadding)
    }

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
