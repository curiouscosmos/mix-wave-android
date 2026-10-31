package org.videolan.vlc.gui.audio

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import org.videolan.vlc.R
import org.videolan.vlc.databinding.AudioPlayerBinding
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.discourse.clipTimestamp
import org.videolan.vlc.discourse.parseClipTimestamp
import org.videolan.vlc.discourse.discourseAudioSnapshot
import org.videolan.vlc.gui.dialogs.ClipDownloadDialog
import org.videolan.vlc.gui.helpers.UiTools

/** View-scoped editor; playback and exports have independent lifetimes. */
internal class ClipEditor(private val player: AudioPlayer, private val binding: AudioPlayerBinding, saved: Bundle?) {
    private val controls: View = binding.root.findViewById(R.id.clip_controls)
    private val start: EditText = controls.findViewById(R.id.clip_start)
    private val end: EditText = controls.findViewById(R.id.clip_end)
    private var audioId: String? = saved?.getString("clip_audio")
    private var range: ClipRange? = if (saved?.getBoolean("clip_open") == true)
        ClipRange(saved.getLong("clip_start"), saved.getLong("clip_end")) else null
    private var duration = 0L

    init {
        binding.playerClip.setBackgroundResource(R.drawable.clip_action_background)
        val normalIcon = binding.playerClip.compoundDrawableTintList?.defaultColor ?: android.graphics.Color.WHITE
        binding.playerClip.compoundDrawableTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(android.graphics.Color.WHITE, normalIcon))
        binding.playerClip.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(android.graphics.Color.WHITE, binding.playerClip.currentTextColor)))
        binding.playerClip.setOnClickListener {
            val progress = player.playlistModel.progress.value ?: return@setOnClickListener
            if (range == null && duration >= 1_000) {
                range = ClipRange.initial(progress.time, duration)
            } else range = null
            finishEditing()
            render()
        }
        binding.clipRange.onRangeChanged = {
            if (start.hasFocus() || end.hasFocus()) finishEditing()
            range = it
            render()
        }
        listOf(R.id.clip_start_minus to Pair(true, -1_000L), R.id.clip_start_plus to Pair(true, 1_000L),
            R.id.clip_end_minus to Pair(false, -1_000L), R.id.clip_end_plus to Pair(false, 1_000L)).forEach { (id, adjustment) ->
            controls.findViewById<View>(id).setOnClickListener {
                if (!commitTimes()) return@setOnClickListener
                val current = range ?: return@setOnClickListener
                range = if (adjustment.first) current.copy(startMs = (current.startMs + adjustment.second).coerceIn(0, current.endMs - 1_000))
                    else current.copy(endMs = (current.endMs + adjustment.second).coerceIn(current.startMs + 1_000, duration))
                finishEditing(); render()
            }
        }
        listOf(start, end).forEach { field ->
            field.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { if (commitTimes()) { finishEditing(); render() }; true } else false
            }
        }
        controls.findViewById<View>(R.id.clip_cut).setOnClickListener {
            if (!commitTimes()) return@setOnClickListener
            finishEditing()
            val audio = player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot() ?: return@setOnClickListener
            val selected = range ?: return@setOnClickListener
            if (player.parentFragmentManager.findFragmentByTag("clip_download") == null)
                ClipDownloadDialog.newInstance(audio, selected).show(player.parentFragmentManager, "clip_download")
        }
        update()
    }

    private fun finishEditing() {
        start.clearFocus()
        end.clearFocus()
        (player.requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    fun update() {
        val media = player.playlistModel.currentMediaWrapper
        val audio = media?.discourseAudioSnapshot()
        val length = player.playlistModel.progress.value?.length?.takeIf { it > 0 }
            ?: media?.length?.takeIf { it > 0 } ?: audio?.durationSeconds?.times(1_000)?.toLong() ?: 0
        if (audioId != audio?.id) { range = null; audioId = audio?.id }
        duration = length
        if (range?.isValid(duration) == false) range = null
        binding.playerClip.isEnabled = audio != null && duration >= 1_000
        binding.playerClip.alpha = if (binding.playerClip.isEnabled) 1f else 0.4f
        render()
    }

    private fun commitTimes(): Boolean {
        if (range == null) return false
        val current = range ?: return false
        val a = if (start.text.toString() == clipTimestamp(current.startMs)) current.startMs else parseClipTimestamp(start.text.toString())
        val b = if (end.text.toString() == clipTimestamp(current.endMs)) current.endMs else parseClipTimestamp(end.text.toString())
        if (a == null || b == null || !ClipRange(a, b).isValid(duration)) {
            UiTools.snacker(player.requireActivity(), R.string.clip_invalid)
            return false
        }
        range = ClipRange(a, b)
        return true
    }

    fun render() {
        val visible = range != null && player.isShowingCover()
        controls.isVisible = visible
        binding.clipRange.isVisible = visible
        val timelineBackground: View = binding.root.findViewById(R.id.player_timeline_background)
        val timelineParams = timelineBackground.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val margin = ((if (visible) 84 else 28) * player.resources.displayMetrics.density).toInt()
        if (timelineParams.bottomMargin != margin) {
            timelineParams.bottomMargin = margin
            timelineBackground.layoutParams = timelineParams
        }
        binding.playerClip.isSelected = range != null
        range?.let {
            if (!start.hasFocus()) start.setText(clipTimestamp(it.startMs))
            if (!end.hasFocus()) end.setText(clipTimestamp(it.endMs))
            controls.findViewById<TextView>(R.id.clip_duration).text = player.getString(R.string.clip_duration, clipTimestamp(it.endMs - it.startMs))
            binding.clipRange.durationMs = duration
            binding.clipRange.timelinePadding = binding.timeline.paddingLeft
            binding.clipRange.setRange(it)
        }
    }

    fun save(out: Bundle) {
        out.putString("clip_audio", audioId)
        out.putBoolean("clip_open", range != null)
        range?.let { out.putLong("clip_start", it.startMs); out.putLong("clip_end", it.endMs) }
    }
}
