package org.videolan.vlc.gui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.videolan.vlc.R
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.discourse.clipTimestamp
import kotlin.math.abs

/** Transparent overlay: touches away from handles fall through to the player's seekbar. */
class ClipRangeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handles = List(2) { View(context) }
    private var dragging = -1
    var durationMs = 0L
    var timelinePadding = 0
    var range = ClipRange(0, 1_000)
        private set
    var onRangeChanged: ((ClipRange) -> Unit)? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        handles.forEachIndexed { index, handle ->
            addView(handle, LayoutParams((48 * density).toInt(), (48 * density).toInt()))
            handle.isFocusable = true
            handle.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN || key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
                else { move(index, if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1_000 else -1_000); true }
            }
            ViewCompat.setAccessibilityDelegate(handle, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.SeekBar"
                    info.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                        AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT, 0f, durationMs / 1_000f,
                        (if (index == 0) range.startMs else range.endMs) / 1_000f)
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                        move(index, if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1_000 else -1_000)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
        }
    }

    fun setRange(value: ClipRange) {
        range = value
        handles[0].contentDescription = "${context.getString(R.string.clip_start)} ${clipTimestamp(value.startMs)}"
        handles[1].contentDescription = "${context.getString(R.string.clip_end)} ${clipTimestamp(value.endMs)}"
        requestLayout()
        invalidate()
    }

    private fun x(time: Long) = timelinePadding + (width - timelinePadding * 2) * (time.toDouble() / durationMs.coerceAtLeast(1)).toFloat()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        handles.forEachIndexed { index, view ->
            val center = x(if (index == 0) range.startMs else range.endMs).toInt()
            val half = (24 * density).toInt()
            view.layout(center - half, 0, center + half, height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(239, 56, 49)
        paint.strokeWidth = 4 * density
        canvas.drawLine(x(range.startMs), height / 2f, x(range.endMs), height / 2f, paint)
        paint.color = Color.WHITE
        listOf(range.startMs, range.endMs).forEach {
            canvas.drawRoundRect(x(it) - 4 * density, height / 2f - 14 * density,
                x(it) + 4 * density, height / 2f + 14 * density, 4 * density, 4 * density, paint)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val startDistance = abs(event.x - x(range.startMs))
            val endDistance = abs(event.x - x(range.endMs))
            dragging = if (minOf(startDistance, endDistance) <= 24 * density) {
                if (startDistance <= endDistance) 0 else 1
            } else -1
        }
        return dragging >= 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dragging < 0 || durationMs < 1_000) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                val time = (((event.x - timelinePadding) / (width - timelinePadding * 2).coerceAtLeast(1)) * durationMs).toLong()
                change(dragging, time)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun move(index: Int, delta: Long) = change(index, (if (index == 0) range.startMs else range.endMs) + delta)

    private fun change(index: Int, time: Long) {
        val value = if (index == 0) range.copy(startMs = time.coerceIn(0, range.endMs - 1_000))
            else range.copy(endMs = time.coerceIn(range.startMs + 1_000, durationMs))
        setRange(value)
        onRangeChanged?.invoke(value)
    }
}
