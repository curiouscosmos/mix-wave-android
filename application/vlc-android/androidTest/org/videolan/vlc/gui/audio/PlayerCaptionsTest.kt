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
    @Test fun captionsAndClipControlsHaveContrastInBothThemesAndOrientations() = onMain {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun color(context: android.content.Context, attr: Int) =
            org.videolan.vlc.gui.helpers.UiTools.getColorFromAttribute(context, attr)
        fun sampleBackground(view: View): Int {
            val bitmap = android.graphics.Bitmap.createBitmap(80, 80, android.graphics.Bitmap.Config.ARGB_8888)
            val drawable = view.background
            val bounds = android.graphics.Rect(drawable.bounds)
            drawable.setBounds(0, 0, 80, 80)
            drawable.draw(android.graphics.Canvas(bitmap))
            drawable.bounds = bounds
            return bitmap.getPixel(40, 40).also { bitmap.recycle() }
        }
        fun contrast(foreground: Int, background: Int, minimum: Double = 4.5) {
            val opaque = androidx.core.graphics.ColorUtils.compositeColors(foreground, background)
            assertTrue("Insufficient contrast: ${Integer.toHexString(foreground)} on ${Integer.toHexString(background)}",
                androidx.core.graphics.ColorUtils.calculateContrast(opaque, background) >= minimum)
        }
        for (theme in listOf(R.style.Theme_VLC, R.style.Theme_VLC_Black)) {
            for (orientation in listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
                val config = Configuration(base.resources.configuration).apply {
                    this.orientation = orientation
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (theme == R.style.Theme_VLC) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                }
                val context = ContextThemeWrapper(base.createConfigurationContext(config), theme)
                val root = LayoutInflater.from(context).inflate(R.layout.audio_player, null)
                val background = sampleBackground(root.findViewById(R.id.caption_panel))
                val panelColor = sampleBackground(root.findViewById(R.id.clip_controls))
                val transcript = root.findViewById<CaptionTextView>(R.id.caption_text)
                transcript.setCues(listOf(org.videolan.vlc.discourse.CaptionCue(0, 1000, "Caption")))
                contrast(transcript.currentTextColor, background)
                transcript.setActive(0)
                transcript.setClipRange(ClipRange(0, 1000))
                val text = transcript.text as android.text.Spanned
                val active = text.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java).single().foregroundColor
                val selected = text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java).single().backgroundColor
                contrast(active, background)
                contrast(active, selected)
                contrast(transcript.currentTextColor, selected)
                for (id in listOf(R.id.clip_start, R.id.clip_end, R.id.clip_start_minus, R.id.clip_start_plus,
                    R.id.clip_end_minus, R.id.clip_end_plus, R.id.clip_duration)) {
                    contrast(root.findViewById<android.widget.TextView>(id).currentTextColor, panelColor)
                }
                val cc = root.findViewById<android.widget.TextView>(R.id.player_cc)
                contrast(cc.currentTextColor, background)
                cc.isSelected = true
                contrast(cc.currentTextColor, color(context, R.attr.colorPrimary))
                val scissors = root.findViewById<androidx.appcompat.widget.AppCompatImageButton>(R.id.player_clip)
                contrast(scissors.supportImageTintList!!.getColorForState(intArrayOf(), 0), background, 3.0)
                contrast(scissors.supportImageTintList!!.getColorForState(intArrayOf(android.R.attr.state_selected), 0),
                    color(context, R.attr.colorPrimary), 3.0)
                val handles = ClipRangeView(context).apply {
                    durationMs = 10000
                    setRange(ClipRange(1000, 8000))
                    measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
                    layout(0, 0, 500, 48)
                }
                val bitmap = android.graphics.Bitmap.createBitmap(500, 48, android.graphics.Bitmap.Config.ARGB_8888)
                handles.draw(android.graphics.Canvas(bitmap))
                contrast(bitmap.getPixel(50, 24), panelColor, 3.0)
                contrast(bitmap.getPixel(400, 24), panelColor, 3.0)
                bitmap.recycle()
            }
        }
    }

    @Test fun captionsFlowTogetherAndOnlyTheSpokenPhraseChangesColor() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_VLC_Black)
        val view = CaptionTextView(context)
        val first = org.videolan.vlc.discourse.CaptionCue(0, 1000, "First\nphrase")
        val second = org.videolan.vlc.discourse.CaptionCue(1000, 2000, "Second phrase")
        view.setCues(listOf(first, second))
        assertEquals("First phrase Second phrase", view.text.toString())
        val text = view.text as android.text.Spannable
        fun highlighted(): String {
            val span = text.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java).single()
            return text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString()
        }
        view.setActive(0)
        assertEquals("First phrase", highlighted())
        view.setActive(1)
        assertEquals("Second phrase", highlighted())
        view.setActive(-1)
        assertTrue(text.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java).isEmpty())
        var clicked: org.videolan.vlc.discourse.CaptionCue? = null
        view.onCueClick = { clicked = it }
        text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)[1].onClick(view)
        assertEquals(second, clicked)
        view.setClipRange(ClipRange(1000, 2000))
        val selected = text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java).single()
        assertEquals("Second phrase", text.subSequence(text.getSpanStart(selected), text.getSpanEnd(selected)).toString())
    }

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
