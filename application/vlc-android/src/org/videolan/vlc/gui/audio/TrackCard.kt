package org.videolan.vlc.gui.audio

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.TextView
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
