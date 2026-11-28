package org.videolan.vlc.gui.audio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import org.videolan.medialibrary.Tools
import org.videolan.vlc.R
import org.videolan.vlc.bookmarks.BookmarkStamp

/** Renders tappable timestamp + bookmark markers above the audio seekbar. */
object BookmarkTimelineRenderer {
    fun render(
        container: ConstraintLayout,
        durationMs: Long,
        stamps: List<BookmarkStamp>,
        onSeek: (Long) -> Unit
    ) {
        container.removeAllViews()
        if (durationMs <= 0L || stamps.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val context = container.context
        val tint = themeColor(context, R.attr.player_icon_color, Color.WHITE)
        val density = context.resources.displayMetrics.density
        val iconSize = (20 * density).toInt()
        val constraints = ConstraintSet()
        constraints.clone(container)
        stamps.sortedBy { it.positionMs }.forEach { stamp ->
            val marker = LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(
                    R.string.bookmark_default_name,
                    Tools.millisToString(stamp.positionMs)
                )
                setOnClickListener { onSeek(stamp.positionMs) }
            }
            marker.addView(TextView(context).apply {
                text = Tools.millisToString(stamp.positionMs)
                setTextColor(tint)
                textSize = 11f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
            })
            marker.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_bookmark_marker)
                imageTintList = ColorStateList.valueOf(tint)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
            container.addView(marker)

            val guideline = View.generateViewId()
            constraints.create(guideline, ConstraintSet.VERTICAL_GUIDELINE)
            constraints.setGuidelinePercent(
                guideline,
                (stamp.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            )
            constraints.connect(marker.id, ConstraintSet.START, guideline, ConstraintSet.START)
            constraints.connect(marker.id, ConstraintSet.END, guideline, ConstraintSet.END)
            constraints.constrainWidth(marker.id, ConstraintSet.WRAP_CONTENT)
            constraints.constrainHeight(marker.id, ConstraintSet.WRAP_CONTENT)
            constraints.connect(marker.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }
        constraints.applyTo(container)
        container.post {
            val width = container.width
            if (width <= 0) return@post
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index)
                child.translationX = when {
                    child.left < 0 -> -child.left.toFloat()
                    child.right > width -> (width - child.right).toFloat()
                    else -> 0f
                }
            }
        }
    }

    private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }
}
