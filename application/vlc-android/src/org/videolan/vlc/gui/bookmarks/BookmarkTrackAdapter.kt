package org.videolan.vlc.gui.bookmarks

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.Tools
import org.videolan.tools.AppScope
import org.videolan.tools.HttpImageLoader
import org.videolan.vlc.R
import org.videolan.vlc.bookmarks.BookmarkedTrack
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.tools.dp

class BookmarkTrackAdapter(
    private val onPlay: (BookmarkedTrack, Long) -> Unit,
    private val onDelete: (BookmarkedTrack, Long) -> Unit
) : ListAdapter<BookmarkedTrack, BookmarkTrackAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.bookmark_track_item, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val artwork = view.findViewById<ImageView>(R.id.bookmark_track_artwork)
        private val title = view.findViewById<TextView>(R.id.bookmark_track_title)
        private val subtitle = view.findViewById<TextView>(R.id.bookmark_track_subtitle)
        private val stamps = view.findViewById<ChipGroup>(R.id.bookmark_track_stamps)

        fun bind(track: BookmarkedTrack) {
            title.text = track.title
            subtitle.text = track.subtitle.orEmpty()
            subtitle.visibility = if (track.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            bindArtwork(track)
            stamps.removeAllViews()
            track.stamps.sortedBy { it.positionMs }.forEach { stamp ->
                stamps.addView(createStamp(track, stamp.positionMs))
            }
        }

        private fun bindArtwork(track: BookmarkedTrack) {
            val context = artwork.context
            artwork.setImageDrawable(UiTools.getDefaultAudioDrawable(context))
            val value = track.artwork
            artwork.tag = value
            if (value.isNullOrBlank()) return
            AppScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    if (value.startsWith("http", true)) HttpImageLoader.downloadBitmap(value)
                    else AudioUtil.readCoverBitmap(value, 160)
                }
                withContext(Dispatchers.Main) {
                    if (artwork.tag == value && bitmap != null) artwork.setImageBitmap(bitmap)
                }
            }
        }

        private fun createStamp(track: BookmarkedTrack, positionMs: Long): Chip = Chip(stamps.context).apply {
            text = Tools.millisToString(positionMs)
            isCheckable = false
            isClickable = true
            isLongClickable = true
            chipIcon = ContextCompat.getDrawable(context, R.drawable.ic_bookmark_marker)
            isChipIconVisible = true
            chipIconSize = 16.dp.toFloat()
            val textColor = themeColor(context, R.attr.font_default, Color.WHITE)
            val backgroundColor = themeColor(context, R.attr.audio_chips_color, Color.TRANSPARENT)
            setTextColor(textColor)
            chipBackgroundColor = ColorStateList.valueOf(backgroundColor)
            chipIconTint = ColorStateList.valueOf(textColor)
            setOnClickListener { onPlay(track, positionMs) }
            setOnLongClickListener {
                onDelete(track, positionMs)
                true
            }
        }

        private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
            val value = TypedValue()
            if (!context.theme.resolveAttribute(attr, value, true)) return fallback
            return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        }
    }

    private object Diff : DiffUtil.ItemCallback<BookmarkedTrack>() {
        override fun areItemsTheSame(oldItem: BookmarkedTrack, newItem: BookmarkedTrack) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: BookmarkedTrack, newItem: BookmarkedTrack) = oldItem == newItem
    }
}
