package org.videolan.vlc.gui.audio

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import org.videolan.vlc.discourse.DiscourseDownloadStore
import org.videolan.vlc.discourse.DiscourseDownloadState
import org.videolan.vlc.R
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.databinding.AudioPlayerBinding
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.discourse.ClipExportOptions
import org.videolan.vlc.discourse.ClipMixerOptions
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
    private var startActive = saved?.getBoolean("clip_start_active", true) ?: true
    val selection get() = range
    val isOpen get() = range != null
    private val downloads = DiscourseDownloadStore(player.requireContext())
    private var pendingPosition: Long? = if (saved?.getBoolean("clip_pending") == true) saved.getLong("clip_position") else null
    private var awaitingDownload = saved?.getBoolean("clip_awaiting_download") == true
    private var pendingRange: ClipRange? = if (saved?.getBoolean("clip_history_pending") == true)
        ClipRange(saved.getLong("clip_history_start"), saved.getLong("clip_history_end")) else null
    private var downloadPrompt: AlertDialog? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewCheck = object : Runnable {
        override fun run() {
            val selected = range
            val service = player.playlistModel.service
            if (selected != null && service != null &&
                player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot()?.id == audioId &&
                !service.mediaplayer.isReleased) {
                // UI progress is throttled to one second; use the native clock for the bracket.
                pauseClipPreviewAtEnd(service, selected, service.mediaplayer.time)
            }
            previewHandler.postDelayed(this, 50)
        }
    }

    init {
        binding.playerClip.setBackgroundResource(R.drawable.clip_action_background)
        val normalIcon = binding.playerClip.supportImageTintList?.defaultColor ?: android.graphics.Color.WHITE
        binding.playerClip.supportImageTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(android.graphics.Color.WHITE, normalIcon))
        binding.playerClip.setOnClickListener {
            if (range == null) {
                startActive = true
                player.openCaptionsForClip()
            }
            player.playlistModel.service?.pause()
            pendingPosition = null
            pendingRange = null
            val progress = player.playlistModel.progress.value ?: return@setOnClickListener
            if (range == null && duration >= 1_000) {
                val audio = player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot() ?: return@setOnClickListener
                if (downloads.verifiedFile(audio) != null) range = ClipRange.initial(progress.time, duration)
                else requestDownload(progress.time)
            } else range = null
            finishEditing()
            render()
        }
        binding.clipRange.onRangeChanged = { value, startChanged ->
            startActive = startChanged
            if (start.hasFocus() || end.hasFocus()) finishEditing()
            select(value, startChanged)
            render()
        }
        binding.clipRange.onHandleSelected = {
            startActive = it
            if (start.hasFocus() || end.hasFocus()) finishEditing()
            render()
        }
        listOf(R.id.clip_start_minus to Pair(true, -1_000L), R.id.clip_start_plus to Pair(true, 1_000L),
            R.id.clip_end_minus to Pair(false, -1_000L), R.id.clip_end_plus to Pair(false, 1_000L)).forEach { (id, adjustment) ->
            controls.findViewById<View>(id).setOnClickListener {
                startActive = adjustment.first
                if (!commitTimes()) return@setOnClickListener
                val current = range ?: return@setOnClickListener
                select(if (adjustment.first) current.copy(startMs = (current.startMs + adjustment.second).coerceIn(0, current.endMs - 1_000))
                    else current.copy(endMs = (current.endMs + adjustment.second).coerceIn(current.startMs + 1_000, duration)), adjustment.first)
                finishEditing(); render()
            }
        }
        listOf(start, end).forEach { field ->
            field.setOnClickListener { startActive = field === start; render() }
            field.setOnFocusChangeListener { _, focused ->
                if (focused) { startActive = field === start; render() }
            }
            field.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { if (commitTimes()) { finishEditing(); render() }; true } else false
            }
        }
        controls.findViewById<View>(R.id.clip_cut).setOnClickListener {
            val service = player.playlistModel.service ?: return@setOnClickListener
            if (service.noiseCancellation.value.loading) {
                UiTools.snacker(player.requireActivity(), R.string.clip_noise_loading)
                return@setOnClickListener
            }
            if (!commitTimes()) return@setOnClickListener
            finishEditing()
            val audio = player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot() ?: return@setOnClickListener
            val selected = range ?: return@setOnClickListener
            if (downloads.verifiedFile(audio) == null) {
                range = null
                requestDownload(selected.startMs)
                render()
                return@setOnClickListener
            }
            val mixer = service.mixerMedia?.takeIf { service.mixerEnabled }?.let {
                ClipMixerOptions(it.uri.toString(), it.title ?: it.uri.lastPathSegment.orEmpty(), service.mixerVolume, service.mixerLoop)
            }
            val options = ClipExportOptions(service.noiseCancellation.value.level, mixer)
            if (player.parentFragmentManager.findFragmentByTag("clip_download") == null)
                ClipDownloadDialog.newInstance(audio, selected, options).show(player.parentFragmentManager, "clip_download")
        }
        update()
        previewHandler.post(previewCheck)
    }

    private fun requestDownload(position: Long) {
        val audio = player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot() ?: return
        pendingPosition = position
        awaitingDownload = true
        downloadPrompt = AlertDialog.Builder(player.requireContext())
            .setMessage(R.string.clip_requires_offline)
            .setNegativeButton(R.string.cancel) { _, _ -> pendingPosition = null; pendingRange = null; awaitingDownload = false }
            .setOnCancelListener { pendingPosition = null; pendingRange = null; awaitingDownload = false }
            .setPositiveButton(R.string.download) { _, _ ->
                downloadPrompt = null
                awaitingDownload = false
                if (player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot()?.id != audio.id) return@setPositiveButton
                player.playlistModel.service?.pause()
                if (downloads.download(audio)) {
                    pendingPosition = position
                    update()
                } else {
                    pendingPosition = null
                    pendingRange = null
                    UiTools.snacker(player.requireActivity(), R.string.discourse_download_failed)
                }
            }.show()
    }

    fun dispose() {
        previewHandler.removeCallbacks(previewCheck)
        downloadPrompt?.dismiss()
        downloadPrompt = null
    }

    private fun select(value: ClipRange, startChanged: Boolean) {
        if (range == value) return
        range = value
        player.playlistModel.service?.let { service ->
            service.pause()
            service.seek(value.previewPosition(startChanged), fromUser = true)
        }
    }

    fun preparePlay(): Boolean {
        if (range == null) return true
        if (!commitTimes()) {
            player.playlistModel.service?.pause()
            return false
        }
        finishEditing()
        range?.let { selected ->
            player.playlistModel.service?.let { service ->
                val time = if (service.mediaplayer.isReleased) service.getTime() else service.mediaplayer.time
                if (time < selected.startMs || time >= selected.endMs)
                    service.seek(selected.startMs, fromUser = true)
            }
        }
        render()
        return true
    }

    private fun finishEditing() {
        val selectedStart = startActive
        start.clearFocus()
        end.clearFocus()
        startActive = selectedStart
        (player.requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    fun update() {
        val media = player.playlistModel.currentMediaWrapper
        // Activity recreation can render before the playback service reconnects.
        if (media == null) { binding.playerClip.isEnabled = false; render(); return }
        val audio = media?.discourseAudioSnapshot()
        val length = player.playlistModel.progress.value?.length?.takeIf { it > 0 }
            ?: media?.length?.takeIf { it > 0 } ?: audio?.durationSeconds?.times(1_000)?.toLong() ?: 0
        if (audioId != audio?.id) {
            startActive = true
            range = null
            pendingPosition = null
            pendingRange = null
            awaitingDownload = false
            downloadPrompt?.dismiss()
            downloadPrompt = null
            audioId = audio?.id
        }
        duration = length
        pendingPosition?.let { position ->
            if (awaitingDownload) {
                if (downloadPrompt?.isShowing != true) requestDownload(position)
            } else if (audio != null && downloads.verifiedFile(audio) != null && duration >= 1_000) {
                player.playlistModel.service?.pause()
                val restored = pendingRange
                range = restored?.takeIf { it.isValid(duration) } ?: if (restored == null) ClipRange.initial(position, duration) else null
                if (restored != null && range != null) {
                    if (!player.isShowingCover()) player.onPlaylistSwitchClick(binding.playlistSwitch)
                    player.openCaptionsForClip()
                    player.playlistModel.service?.seek(restored.startMs, fromUser = true)
                } else if (restored != null) UiTools.snacker(player.requireActivity(), R.string.clip_invalid)
                pendingRange = null
                pendingPosition = null
            } else if (audio == null || downloads.state(audio) != DiscourseDownloadState.DOWNLOADING) {
                pendingPosition = null
                pendingRange = null
                UiTools.snacker(player.requireActivity(), R.string.discourse_download_failed)
            }
        }
        if (range?.isValid(duration) == false) range = null
        binding.playerClip.isEnabled = audio != null && duration >= 1_000
        binding.playerClip.alpha = if (binding.playerClip.isEnabled) 1f else 0.4f
        controls.findViewById<View>(R.id.clip_cut).isEnabled = player.playlistModel.service?.noiseCancellation?.value?.loading != true
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
        select(ClipRange(a, b), a != current.startMs)
        return true
    }

    fun render() {
        binding.clipRange.startActive = startActive
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
        player.refreshCaptionRange()
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
        out.putBoolean("clip_history_pending", pendingRange != null)
        pendingRange?.let { out.putLong("clip_history_start", it.startMs); out.putLong("clip_history_end", it.endMs) }
        out.putBoolean("clip_start_active", startActive)
        out.putString("clip_audio", audioId)
        out.putBoolean("clip_pending", pendingPosition != null)
        out.putBoolean("clip_awaiting_download", awaitingDownload)
        pendingPosition?.let { out.putLong("clip_position", it) }
        out.putBoolean("clip_open", range != null)
        range?.let { out.putLong("clip_start", it.startMs); out.putLong("clip_end", it.endMs) }
    }

    fun selectCaption(cue: org.videolan.vlc.discourse.CaptionCue) {
        val current = range ?: return
        val value = org.videolan.vlc.discourse.captionRange(current, cue, startActive, duration)
        if (value == null) {
            UiTools.snacker(player.requireActivity(), R.string.caption_invalid_range)
            return
        }
        finishEditing()
        select(value, startActive)
        render()
    }

    fun restoreHistory(id: String, selected: ClipRange) {
        if (player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot()?.id != id) return
        update()
        if (!selected.isValid(duration)) {
            UiTools.snacker(player.requireActivity(), R.string.clip_invalid)
            return
        }
        val audio = player.playlistModel.currentMediaWrapper?.discourseAudioSnapshot() ?: return
        startActive = true
        if (downloads.verifiedFile(audio) == null) {
            pendingRange = selected
            requestDownload(selected.startMs)
            return
        }
        pendingPosition = null
        pendingRange = null
        range = selected
        finishEditing()
        if (!player.isShowingCover()) player.onPlaylistSwitchClick(binding.playlistSwitch)
        player.openCaptionsForClip()
        player.playlistModel.service?.let { it.pause(); it.seek(selected.startMs, fromUser = true) }
        render()
    }
}

internal fun pauseClipPreviewAtEnd(service: PlaybackService, range: ClipRange, timeMs: Long) {
    if (service.isPlaying && timeMs >= range.endMs) {
        service.pause()
        service.seek(range.endMs, fromUser = true)
    }
}
