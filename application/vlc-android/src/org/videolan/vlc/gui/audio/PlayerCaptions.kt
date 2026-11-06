package org.videolan.vlc.gui.audio

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.videolan.vlc.R
import org.videolan.vlc.databinding.AudioPlayerBinding
import org.videolan.vlc.discourse.*

/** View-scoped transcript; the playback service remains the only playback clock. */
internal class PlayerCaptions(
    private val player: AudioPlayer, private val binding: AudioPlayerBinding,
    private val editor: ClipEditor, saved: Bundle?
) {
    private val panel: View = binding.root.findViewById(R.id.caption_panel)
    private val button: View = binding.root.findViewById(R.id.player_cc)
    private val list: RecyclerView = panel.findViewById(R.id.caption_list)
    private val status: TextView = panel.findViewById(R.id.caption_status)
    private val retry: View = panel.findViewById(R.id.caption_retry)
    private val follow: View = panel.findViewById(R.id.caption_follow)
    private val scope = player.viewLifecycleOwner.lifecycleScope
    private val loader = DiscourseCaptions(player.requireContext())
    private var audio: DiscourseAudio? = null
    private var currentId: String? = saved?.getString("captions_audio")
    private var initialized = false
    private var requested = saved?.getBoolean("captions_visible") == true
    private var followPlayback = saved?.getBoolean("captions_follow", true) ?: true
    private var cues = emptyList<CaptionCue>()
    private var active = -1
    private var slideOffset = 0f
    private var selectedRange: ClipRange? = null
    private val rows = object : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = cues.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val text = TextView(parent.context)
            text.layoutParams = RecyclerView.LayoutParams(-1, -2)
            val padding = (16 * text.resources.displayMetrics.density).toInt()
            text.setPadding(padding, padding, padding, padding)
            text.textSize = 22f
            text.isFocusable = true
            text.isClickable = true
            return Row(text)
        }
        override fun onBindViewHolder(holder: Row, position: Int) {
            val cue = cues[position]
            holder.text.text = cue.text
            holder.text.setTextColor(if (position == active) Color.rgb(255, 110, 90) else Color.WHITE)
            holder.text.isSelected = position == active
            holder.text.setBackgroundColor(if (selectedRange?.let { cue.startMs < it.endMs && cue.endMs > it.startMs } == true)
                0x303F8CFF else Color.TRANSPARENT)
            holder.text.setOnClickListener {
                if (editor.isOpen) editor.selectCaption(cue)
                else player.playlistModel.service?.seek(cue.startMs, fromUser = true)
            }
        }
    }

    private val requests by lazy {
        CaptionLoader(scope, { original ->
            DiscourseRepository(player.requireContext()).getDiscourseAudios(original.discourseId, forceRefresh = true)
                .data.firstOrNull { it.id == original.id }
        }, { loader.load(it) }) { updated, result, error ->
            if (player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot()?.id != updated.id) return@CaptionLoader
            audio = updated
            player.playlistModel.currentMediaWrapper?.takeIf { it.discourseAudioSnapshot()?.id == updated.id }
                ?.updateCaptionSnapshot(updated)
            cues = result.orEmpty()
            active = -1
            rows.notifyDataSetChanged()
            status.setText(if (error) R.string.caption_error else R.string.caption_empty)
            status.isVisible = error || cues.isEmpty()
            retry.isVisible = error || cues.isEmpty()
            render()
        }
    }

    init {
        list.layoutManager = LinearLayoutManager(player.requireContext())
        list.adapter = rows
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    followPlayback = false
                    follow.isVisible = true
                }
            }
        })
        button.setOnClickListener { requested = !requested; if (requested) load(false); render() }
        retry.setOnClickListener { load(true) }
        follow.setOnClickListener {
            followPlayback = true
            follow.isVisible = false
            if (active >= 0) list.smoothScrollToPosition(active)
        }
        scope.launch {
            player.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    render()
                    if (panel.isVisible) highlight()
                    delay(if (panel.isVisible) 50 else 250)
                }
            }
        }
        update()
    }

    fun update() {
        val media = player.playlistModel.currentMediaWrapper
        val snapshot = media?.discourseAudioSnapshot()
        if (snapshot == null) {
            requests.select(null)
            audio = null
            initialized = false
            if (media != null) { currentId = null; requested = false; followPlayback = true }
            if (cues.isNotEmpty()) { cues = emptyList(); active = -1; rows.notifyDataSetChanged() }
            render()
            return
        }
        if (initialized && currentId == snapshot.id) return
        val changed = currentId != null && currentId != snapshot.id
        initialized = true
        currentId = snapshot.id
        requests.select(snapshot)
        audio = snapshot
        cues = emptyList()
        active = -1
        rows.notifyDataSetChanged()
        if (changed) { requested = false; followPlayback = true }
        render()
        if (requested) load(false)
    }

    private fun load(refresh: Boolean) {
        if (!requests.request(refresh) || !requests.isLoading) return
        status.setText(R.string.caption_loading)
        status.isVisible = true
        retry.isVisible = false
    }

    fun openForClip() { requested = true; followPlayback = true; load(false); render() }

    fun onSlide(offset: Float) { slideOffset = offset; render() }

    fun render() {
        if (binding.expandedActions == null) {
            val params = panel.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val bottom = if (editor.isOpen) R.id.clip_controls else R.id.player_timeline_background
            if (params.bottomToTop != bottom) { params.bottomToTop = bottom; panel.layoutParams = params }
        }
        val available = audio != null
        button.isEnabled = available
        button.alpha = slideOffset * if (available) 1f else .4f
        button.isSelected = available && requested
        button.isVisible = player.isShowingCover() && slideOffset > 0f
        panel.isVisible = available && requested && player.isShowingCover() && slideOffset > 0f && binding.coverMediaSwitcher.isVisible
        panel.alpha = slideOffset
        binding.coverMediaSwitcher.importantForAccessibility = if (panel.isVisible)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        follow.isVisible = !followPlayback
        val value = editor.selection
        if (selectedRange != value) {
            val old = selectedRange
            selectedRange = value
            cues.forEachIndexed { index, cue ->
                fun inside(range: ClipRange?) = range?.let { cue.startMs < it.endMs && cue.endMs > it.startMs } == true
                if (inside(old) != inside(value)) rows.notifyItemChanged(index)
            }
        }
    }

    private fun highlight() {
        val service = player.playlistModel.service ?: return
        if (service.mediaplayer.isReleased) return
        val time = service.mediaplayer.time
        // Binary search keeps a long transcript cheap, including gaps between cues.
        val next = activeCaption(cues, time)
        if (next == active) return
        val previous = active
        active = next
        if (previous >= 0) rows.notifyItemChanged(previous)
        if (next >= 0) {
            rows.notifyItemChanged(next)
            if (followPlayback) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(next, list.height / 3)
        }
    }

    fun save(out: Bundle) {
        out.putString("captions_audio", currentId)
        out.putBoolean("captions_visible", requested)
        out.putBoolean("captions_follow", followPlayback)
    }

    fun dispose() { requests.cancel(); list.adapter = null; list.clearOnScrollListeners() }
    private class Row(val text: TextView) : RecyclerView.ViewHolder(text)
}
