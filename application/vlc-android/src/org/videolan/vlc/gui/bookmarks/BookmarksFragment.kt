package org.videolan.vlc.gui.bookmarks

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.videolan.medialibrary.Tools
import org.videolan.vlc.R
import org.videolan.vlc.bookmarks.BookmarkPlayback
import org.videolan.vlc.bookmarks.BookmarkedTrack
import org.videolan.vlc.bookmarks.bookmarkKey
import org.videolan.vlc.gui.AudioPlayerContainerActivity
import org.videolan.vlc.gui.BaseFragment
import org.videolan.vlc.media.PlaylistManager

class BookmarksFragment : BaseFragment() {
    private val model: BookmarksViewModel by viewModels()
    private var adapter: BookmarkTrackAdapter? = null
    private var hasMore = false
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var loading: View
    private var pendingExpandKey: String? = null

    override fun getTitle() = getString(R.string.bookmarks_title)

    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.bookmarks_list)
        empty = view.findViewById(R.id.bookmarks_empty)
        loading = view.findViewById(R.id.bookmarks_loading)
        val trackAdapter = BookmarkTrackAdapter(::play, ::confirmDelete)
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
                launch {
                    model.hasMoreTracks.collect { hasMore = it }
                }
                launch {
                    model.isLoading.collect { loading.isVisible = it }
                }
            }
        }
    }

    private fun play(track: BookmarkedTrack, positionMs: Long) {
        val activity = activity as? AudioPlayerContainerActivity
        pendingExpandKey = track.key
        activity?.expandAudioPlayerForTrack(track.key)
        if (!BookmarkPlayback.play(requireContext(), track, positionMs)) {
            pendingExpandKey = null
            org.videolan.vlc.gui.helpers.UiTools.snacker(requireActivity(), R.string.bookmark_play_failed)
        }
    }

    private fun confirmDelete(track: BookmarkedTrack, positionMs: Long) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bookmark_delete_title)
            .setMessage(getString(R.string.bookmark_delete_message, Tools.millisToString(positionMs)))
            .setPositiveButton(R.string.delete) { _, _ ->
                model.remove(track.key, positionMs)
                list.announceForAccessibility(getString(R.string.bookmark_deleted))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        list.adapter = null
        adapter = null
        super.onDestroyView()
    }
}
