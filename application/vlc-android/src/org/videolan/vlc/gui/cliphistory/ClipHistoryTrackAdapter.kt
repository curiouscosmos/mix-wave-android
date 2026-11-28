package org.videolan.vlc.gui.cliphistory

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
import org.videolan.tools.AppScope
import org.videolan.tools.HttpImageLoader
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.discourse.clipTimestamp
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.mediadb.models.ClipHistory

class ClipHistoryTrackAdapter(
    private val onPlay: (ClipHistoryTrack, Long) -> Unit
) : ListAdapter<ClipHistoryTrack, ClipHistoryTrackAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.clip_history_track_item, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val artwork = view.findViewById<ImageView>(R.id.clip_history_track_artwork)
        private val title = view.findViewById<TextView>(R.id.clip_history_track_title)
        private val subtitle = view.findViewById<TextView>(R.id.clip_history_track_subtitle)
        private val clips = view.findViewById<ChipGroup>(R.id.clip_history_track_clips)

        fun bind(track: ClipHistoryTrack) {
            title.text = track.title
            subtitle.text = track.subtitle.orEmpty()
            subtitle.visibility = if (track.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            bindArtwork(track)
            clips.removeAllViews()
            track.clips.forEach { clips.addView(createClip(track, it)) }
        }

        private fun bindArtwork(track: ClipHistoryTrack) {
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

        private fun createClip(track: ClipHistoryTrack, clip: ClipHistory): Chip = Chip(clips.context).apply {
            text = context.getString(R.string.clip_range, clipTimestamp(clip.startMs), clipTimestamp(clip.endMs))
            isCheckable = false
            isClickable = true
            chipIcon = ContextCompat.getDrawable(context, R.drawable.ic_player_clip)
            isChipIconVisible = true
            chipIconSize = 16.dp.toFloat()
            val textColor = themeColor(context, R.attr.font_default, Color.WHITE)
            val backgroundColor = themeColor(context, R.attr.audio_chips_color, Color.TRANSPARENT)
            setTextColor(textColor)
            chipBackgroundColor = ColorStateList.valueOf(backgroundColor)
            chipIconTint = ColorStateList.valueOf(textColor)
            contentDescription = context.getString(
                R.string.clip_history_play_description,
                clipTimestamp(clip.startMs),
                clipTimestamp(clip.endMs)
            )
            setOnClickListener { onPlay(track, clip.startMs) }
        }

        private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
            val value = TypedValue()
            if (!context.theme.resolveAttribute(attr, value, true)) return fallback
            return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        }
    }

    private object Diff : DiffUtil.ItemCallback<ClipHistoryTrack>() {
        override fun areItemsTheSame(oldItem: ClipHistoryTrack, newItem: ClipHistoryTrack) =
            oldItem.audioId == newItem.audioId

        override fun areContentsTheSame(oldItem: ClipHistoryTrack, newItem: ClipHistoryTrack) =
            oldItem == newItem
    }
}
