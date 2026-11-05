package org.videolan.vlc.gui.audio

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.vlc.R
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.gui.view.ClipRangeView

@RunWith(AndroidJUnit4::class)
class PlayerCaptionsTest {
    @Test fun touchingEitherHandleActivatesWithoutMovingTheRange() = onMain {
        val view = rangeView()
        var activeStart = true
        view.onHandleSelected = { activeStart = it }
        fun tap(x: Float) {
            val time = android.os.SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, x, 24f, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        repeat(3) {
            tap(400f)
            assertFalse(activeStart)
            assertTrue(view.getChildAt(1).isSelected)
            assertEquals(ClipRange(1000, 8000), view.range)
        }
        tap(50f)
        assertTrue(activeStart)
        assertTrue(view.getChildAt(0).isSelected)
        view.getChildAt(1).performClick()
        assertFalse(activeStart)
        assertEquals(ClipRange(1000, 8000), view.range)
    }

    @Test fun keyboardAndAccessibilityAdjustAndSelectTheCorrespondingBracket() = onMain {
        val view = rangeView()
        view.getChildAt(1).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(view.startActive)
        assertEquals(ClipRange(1000, 7000), view.range)
        assertTrue(view.getChildAt(0).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertTrue(view.startActive)
        assertEquals(ClipRange(2000, 7000), view.range)
    }

    @Test fun bothLayoutsHaveCaptionsAndKeepClipControlsOutsideTranscript() = onMain {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (orientation in listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
            val config = Configuration(base.resources.configuration).apply { this.orientation = orientation }
            val context = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_VLC_Black)
            val root = LayoutInflater.from(context).inflate(R.layout.audio_player, null)
            val panel = root.findViewById<View>(R.id.caption_panel)
            assertNotNull(panel.findViewById<View>(R.id.caption_list))
            assertNotNull(root.findViewById<View>(R.id.player_cc))
            assertNotNull(root.findViewById<View>(R.id.clip_controls))
            assertNotNull(root.findViewById<View>(R.id.timeline))
            assertNotNull(root.findViewById<View>(R.id.clip_cut))
            assertNull(panel.findViewById<View>(R.id.clip_cut))
            root.findViewById<View>(R.id.songs_list).visibility = View.GONE
            for (id in listOf(R.id.cover_media_switcher, R.id.caption_panel, R.id.clip_controls,
                R.id.player_cc, R.id.player_clip, R.id.player_download, R.id.noise_cancellation))
                root.findViewById<View>(id).visibility = View.VISIBLE
            root.findViewById<View>(R.id.expanded_actions)?.visibility = View.VISIBLE
            panel.findViewById<View>(R.id.caption_status).visibility = View.GONE
            panel.findViewById<View>(R.id.caption_retry).visibility = View.GONE
            panel.findViewById<View>(R.id.caption_follow).visibility = View.GONE
            val density = context.resources.displayMetrics.density
            val timeline = root.findViewById<View>(R.id.player_timeline_background)
            (timeline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).bottomMargin = (84 * density).toInt()
            val portrait = orientation == Configuration.ORIENTATION_PORTRAIT
            val width = ((if (portrait) 412 else 800) * density).toInt()
            val height = ((if (portrait) 800 else 412) * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertTrue("Transcript has usable height in orientation $orientation: ${panel.height / density} dp", panel.height >= 96 * density)
            assertTrue("Transcript stays above clipping controls in orientation $orientation",
                panel.bottom <= root.findViewById<View>(R.id.clip_controls).top)
        }
    }

    private fun rangeView(): ClipRangeView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ClipRangeView(context).apply {
            durationMs = 10000
            setRange(ClipRange(1000, 8000))
            measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
            layout(0, 0, 500, 48)
            // The view is intentionally unattached; touch handling still needs a parent.
            android.widget.FrameLayout(context).addView(this)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
