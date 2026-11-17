package org.videolan.vlc.gui.audio

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.TextView
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
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

internal fun TextView.bindTrackStats(stats: TrackCardStats) {
    visibility = android.view.View.VISIBLE
    tag = stats
    val parts = mutableListOf(resources.getQuantityString(R.plurals.track_card_plays,
        stats.plays.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), stats.plays.coerceAtLeast(0)))
    if (stats.likes > 1) parts += resources.getQuantityString(R.plurals.track_card_likes, stats.likes, stats.likes)
    val label = parts.joinToString(" | ")
    val icon = AppCompatResources.getDrawable(context, R.drawable.ic_download)!!.mutate()
    val size = (18 * resources.displayMetrics.density).toInt()
    icon.setBounds(0, 0, size, size)
    icon.setTint(if (stats.downloaded) context.getColor(R.color.green500) else Color.rgb(166, 157, 159))
    text = SpannableStringBuilder("$label | \uFFFC").apply {
        setSpan(ImageSpan(icon, ImageSpan.ALIGN_BASELINE), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    contentDescription = "$label. ${context.getString(if (stats.downloaded) R.string.discourse_downloaded else R.string.not_downloaded)}"
}

internal fun TextView.bindLocalTrackFavourite(item: MediaWrapper, owner: LifecycleOwner) {
    tag = item
    text = ""
    val icon = AppCompatResources.getDrawable(context,
        if (item.isFavorite) R.drawable.ic_header_media_favorite else R.drawable.ic_header_media_favorite_outline)!!.mutate()
    val size = (24 * resources.displayMetrics.density).toInt()
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
