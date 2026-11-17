package org.videolan.vlc.gui.audio

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.TextView
import android.widget.ProgressBar
import androidx.core.view.isVisible
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.R

internal fun trackListeningProgress(time: Long, length: Long): Int =
    if (length > 0) (time.toDouble() / length * 100).toInt().coerceIn(0, 100) else 0

internal data class TrackCardStats(val plays: Long, val likes: Int, val downloaded: Boolean)

internal fun ProgressBar.bindTrackProgress(percent: Int) {
    progress = percent
    isVisible = percent >= 1
}

internal fun TextView.bindTrackStats(stats: TrackCardStats) {
    visibility = android.view.View.VISIBLE
    tag = stats
    val label = SpannableStringBuilder()
    val descriptions = mutableListOf<String>()
    val neutral = Color.rgb(166, 157, 159)
    fun appendBadge(resource: Int, caption: String = "", tint: Int = Color.WHITE) {
        if (label.isNotEmpty()) label.append("  ")
        val icon = AppCompatResources.getDrawable(context, resource)!!.mutate()
        icon.setTint(tint)
        val start = label.length
        label.append(caption.ifEmpty { "\uFFFC" })
        label.setSpan(TrackBadgeSpan(icon, caption, resources.displayMetrics.density),
            start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (stats.plays > 0) {
        val caption = resources.getQuantityString(R.plurals.track_card_plays,
            stats.plays.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), stats.plays)
        appendBadge(R.drawable.ic_player_play_solid, caption)
        descriptions += caption
    }
    if (stats.likes > 0) {
        val caption = resources.getQuantityString(R.plurals.track_card_likes, stats.likes, stats.likes)
        appendBadge(R.drawable.ic_header_media_favorite_outline, caption)
        descriptions += caption
    }
    appendBadge(R.drawable.ic_download, tint = if (stats.downloaded) context.getColor(R.color.green500) else neutral)
    descriptions += context.getString(if (stats.downloaded) R.string.discourse_downloaded else R.string.not_downloaded)
    text = label
    contentDescription = descriptions.joinToString(". ")
}

internal class TrackBadgeSpan(private val icon: Drawable, private val caption: String, density: Float) : ReplacementSpan() {
    private val padding = 8 * density
    private val iconSize = (16 * density).toInt()
    private val gap = 4 * density
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }

    private fun height(paint: Paint) = maxOf(iconSize.toFloat(), paint.descent() - paint.ascent()) + padding

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val extra = kotlin.math.ceil((height(paint) - (paint.descent() - paint.ascent())) / 2).toInt()
        fm?.apply {
            ascent = paint.fontMetricsInt.ascent - extra
            descent = paint.fontMetricsInt.descent + extra
            top = ascent
            bottom = descent
        }
        return kotlin.math.ceil(2 * padding + iconSize + if (caption.isEmpty()) 0f else gap + paint.measureText(caption)).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val center = y + (paint.ascent() + paint.descent()) / 2
        val radius = height(paint) / 2
        val right = x + getSize(paint, text, start, end, null)
        brush.style = Paint.Style.FILL
        brush.color = 0x22B96C57
        canvas.drawRoundRect(x + 1, center - radius + 1, right - 1, center + radius - 1, radius, radius, brush)
        brush.style = Paint.Style.STROKE
        brush.color = 0xFF79514B.toInt()
        canvas.drawRoundRect(x + 1, center - radius + 1, right - 1, center + radius - 1, radius, radius, brush)
        val left = (x + padding).toInt()
        val iconTop = (center - iconSize / 2f).toInt()
        icon.setBounds(left, iconTop, left + iconSize, iconTop + iconSize)
        icon.draw(canvas)
        canvas.drawText(caption, x + padding + iconSize + gap, y.toFloat(), paint)
    }
}

internal fun TextView.bindLocalTrackFavourite(item: MediaWrapper, owner: LifecycleOwner) {
    tag = item
    text = ""
    val icon = AppCompatResources.getDrawable(context,
        if (item.isFavorite) R.drawable.ic_header_media_favorite else R.drawable.ic_header_media_favorite_outline)!!.mutate()
    val size = (32 * resources.displayMetrics.density).toInt()
    val padding = (8 * resources.displayMetrics.density).toInt()
    setPaddingRelative(padding, 0, padding, 0)
    icon.setBounds(0, 0, size, size)
    setCompoundDrawablesRelative(icon, null, null, null)
    compoundDrawableTintList = ColorStateList.valueOf(Color.parseColor(if (item.isFavorite) "#FF4D52" else "#C9BDBD"))
    contentDescription = context.getString(
        if (item.isFavorite) R.string.discourse_unlike_description else R.string.discourse_like_description, item.title, 0)
    setOnClickListener {
        owner.lifecycleScope.launch {
            isEnabled = false
            try {
                withContext(Dispatchers.IO) { item.isFavorite = !item.isFavorite }
                if (tag === item) bindLocalTrackFavourite(item, owner)
            } finally {
                isEnabled = true
            }
        }
    }
}
