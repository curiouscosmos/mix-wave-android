package org.videolan.vlc.gui.cliphistory

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.videolan.vlc.R
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.bookmarks.bookmarkKey
import org.videolan.vlc.discourse.discoursePlaybackIds
import org.videolan.vlc.discourse.playDiscourseAudio
import org.videolan.vlc.gui.AudioPlayerContainerActivity
import org.videolan.vlc.gui.BaseFragment
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.media.PlaylistManager

class ClipHistoryFragment : BaseFragment() {
    private val model: ClipHistoryViewModel by viewModels()
    private var adapter: ClipHistoryTrackAdapter? = null
    private var hasMore = false
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var loading: View
    private var pendingExpandKey: String? = null

    override fun getTitle() = getString(R.string.clip_history)

    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.clip_history_list)
        empty = view.findViewById(R.id.clip_history_empty)
        loading = view.findViewById(R.id.clip_history_loading)
        val trackAdapter = ClipHistoryTrackAdapter(::play)
        adapter = trackAdapter
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = trackAdapter
        PlaylistManager.currentPlayedMedia.observe(viewLifecycleOwner) { media ->
            val key = pendingExpandKey ?: return@observe
            if (media?.bookmarkKey() == key) {
                pendingExpandKey = null
                (activity as? AudioPlayerContainerActivity)?.expandAudioPlayerForTrack(key)
            }
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore) return
                val last = (recyclerView.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: -1
                if (last >= trackAdapter.itemCount - 3) model.loadNextPage()
            }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.tracks.collect { tracks ->
                        trackAdapter.submitList(tracks)
                        empty.isVisible = tracks.isEmpty()
                    }
                }
                launch { model.hasMoreTracks.collect { hasMore = it } }
                launch { model.isLoading.collect { loading.isVisible = it } }
            }
        }
    }

    private fun play(track: ClipHistoryTrack, startMs: Long) {
        val audio = track.audio
        if (audio == null) {
            UiTools.snacker(requireActivity(), R.string.clip_history_play_failed)
            return
        }
        val key = "discourse:${audio.id}"
        val activity = activity as? AudioPlayerContainerActivity
        pendingExpandKey = key
        activity?.expandAudioPlayerForTrack(key)
        val service = PlaybackService.instance
        if (service?.currentMediaWrapper?.discoursePlaybackIds()?.audioId == audio.id) {
            service.setTime(startMs)
            pendingExpandKey = null
        } else {
            requireContext().playDiscourseAudio(audio, startAt = startMs)
        }
    }

    override fun onDestroyView() {
        if (::list.isInitialized) list.adapter = null
        adapter = null
        super.onDestroyView()
    }
}
