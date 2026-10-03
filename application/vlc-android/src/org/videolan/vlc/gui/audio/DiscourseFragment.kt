package org.videolan.vlc.gui.audio

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ActionMode
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.videolan.tools.HttpImageLoader
import org.videolan.medialibrary.Tools
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseDownloadState
import org.videolan.vlc.discourse.DiscourseDownloadStore
import org.videolan.vlc.discourse.DiscoursePlaybackStore
import org.videolan.vlc.discourse.playDiscourseAudios
import org.videolan.vlc.discourse.shouldEnqueue
import org.videolan.vlc.gui.BaseFragment
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.view.SwipeRefreshLayout
import org.videolan.vlc.viewmodels.DiscourseViewModel
import org.videolan.resources.util.registerReceiverCompat

class DiscourseFragment : BaseFragment() {
    private val model: DiscourseViewModel by viewModels { DiscourseViewModel.Factory(requireContext()) }
    private lateinit var grid: RecyclerView
    private lateinit var gridSwipe: SwipeRefreshLayout
    private lateinit var catalogue: View
    private lateinit var catalogueEmpty: TextView
    private lateinit var languageFilter: Spinner
    private lateinit var sortFilter: Spinner
    private lateinit var detail: View
    private lateinit var tracks: RecyclerView
    private lateinit var tracksSwipe: SwipeRefreshLayout
    private lateinit var state: View
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var retry: Button
    private lateinit var backCallback: OnBackPressedCallback
    private lateinit var downloads: DiscourseDownloadStore
    private lateinit var playbackStore: DiscoursePlaybackStore
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            tracks.adapter?.notifyDataSetChanged()
        }
    }

    override fun getTitle() = getString(R.string.discourse)

    fun openDiscourse(discourse: Discourse) {
        model.openDiscourse(discourse)
    }
    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.discourse_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloads = DiscourseDownloadStore(requireContext())
        playbackStore = DiscoursePlaybackStore(requireContext())
        grid = view.findViewById(R.id.discourse_grid)
        gridSwipe = view.findViewById(R.id.discourse_grid_swipe)
        catalogue = view.findViewById(R.id.discourse_catalogue)
        catalogueEmpty = view.findViewById(R.id.discourse_catalogue_empty)
        languageFilter = view.findViewById(R.id.discourse_language_filter)
        sortFilter = view.findViewById(R.id.discourse_sort_filter)
        languageFilter.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, resources.getStringArray(R.array.discourse_languages)).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sortFilter.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, resources.getStringArray(R.array.discourse_sorts)).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        languageFilter.setSelection(model.languageFilter.ordinal)
        sortFilter.setSelection(model.sortFilter.ordinal)
        val filtersChanged = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                model.setFilters(
                    DiscourseViewModel.LanguageFilter.values()[languageFilter.selectedItemPosition],
                    DiscourseViewModel.SortFilter.values()[sortFilter.selectedItemPosition]
                )
            }
        }
        languageFilter.onItemSelectedListener = filtersChanged
        sortFilter.onItemSelectedListener = filtersChanged
        detail = view.findViewById(R.id.discourse_detail)
        tracks = view.findViewById(R.id.discourse_tracks)
        tracksSwipe = view.findViewById(R.id.discourse_tracks_swipe)
        state = view.findViewById(R.id.discourse_state)
        progress = view.findViewById(R.id.discourse_progress)
        message = view.findViewById(R.id.discourse_message)
        retry = view.findViewById(R.id.discourse_retry)
        grid.layoutManager = LinearLayoutManager(requireContext())
        tracks.layoutManager = LinearLayoutManager(requireContext())
        gridSwipe.setOnRefreshListener { model.refresh() }
        tracksSwipe.setOnRefreshListener { model.retryDetail() }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) model.loadMore()
            }
        })
        view.findViewById<ImageButton>(R.id.discourse_back).setOnClickListener { model.back() }
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = model.back()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        model.state.observe(viewLifecycleOwner, ::render)
        viewLifecycleOwner.lifecycleScope.launch {
            playbackStore.initialize()
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshProgress(null)
                playbackStore.changes.collect { refreshProgress(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.load()
        (tracks.adapter as? TrackAdapter)?.refreshDownloads()
    }

    override fun onStart() {
        super.onStart()
        requireContext().registerReceiverCompat(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), true)
    }

    override fun onStop() {
        requireContext().unregisterReceiver(downloadReceiver)
        super.onStop()
    }

    private fun render(value: DiscourseViewModel.State) {
        gridSwipe.isRefreshing = false
        tracksSwipe.isRefreshing = false
        retry.setOnClickListener(null)
        when (value) {
            DiscourseViewModel.State.Idle, DiscourseViewModel.State.Loading -> showState(null, loading = true)
            DiscourseViewModel.State.Error -> showState(getString(R.string.discourse_load_failed)) { model.load() }
            is DiscourseViewModel.State.Catalogue -> {
                backCallback.isEnabled = false
                detail.isVisible = false
                catalogue.isVisible = true
                if (value.discourses.isEmpty()) {
                    state.isVisible = false
                    catalogueEmpty.isVisible = true
                    gridSwipe.isVisible = false
                    grid.isVisible = false
                    (grid.adapter as? DiscourseAdapter)?.update(emptyList())
                        ?: run { grid.adapter = DiscourseAdapter(emptyList(), model::select) }
                } else {
                    state.isVisible = false
                    catalogueEmpty.isVisible = false
                    gridSwipe.isVisible = true
                    grid.isVisible = true
                    (grid.adapter as? DiscourseAdapter)?.update(value.discourses)
                        ?: run { grid.adapter = DiscourseAdapter(value.discourses, model::select) }
                }
            }
            is DiscourseViewModel.State.Detail -> {
                backCallback.isEnabled = true
                catalogue.isVisible = false
                grid.isVisible = false
                detail.isVisible = true
                value.tracks?.let { audios ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { playbackStore.register(audios, value.discourse.id) }
                        refreshProgress(null)
                    }
                }
                refreshProgress(null)
                val image = detail.findViewById<ImageView>(R.id.discourse_detail_image)
                detail.findViewById<TextView>(R.id.discourse_detail_title).text = value.discourse.title
                loadImage(image, value.discourse.thumbnailUrl)
                when {
                    value.error -> showState(getString(R.string.discourse_tracks_failed)) { model.retryDetail() }
                    value.tracks == null -> showState(null, loading = true)
                    value.tracks.isEmpty() -> showState(getString(R.string.discourse_no_tracks))
                    else -> {
                        state.isVisible = false
                        detail.findViewById<Button>(R.id.discourse_download_all).setOnClickListener {
                            if (value.tracks.filter { shouldEnqueue(downloads.state(it)) }.any { !downloads.download(it) }) {
                                Toast.makeText(requireContext(), R.string.discourse_download_failed, Toast.LENGTH_SHORT).show()
                            }
                            tracks.adapter?.notifyDataSetChanged()
                        }
                        detail.findViewById<Button>(R.id.discourse_remove_all).setOnClickListener {
                            AlertDialog.Builder(requireContext())
                                .setMessage(R.string.discourse_remove_all_confirm)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.discourse_remove_download) { _, _ ->
                                    value.tracks.forEach(downloads::remove)
                                    tracks.adapter?.notifyDataSetChanged()
                                }.show()
                        }
                        tracks.adapter = TrackAdapter(value.tracks) { position ->
                            model.recordRecentlyPlayed(value.discourse)
                            requireContext().playDiscourseAudios(value.tracks, position)
                        }
                    }
                }
            }
        }
    }

    private fun refreshProgress(change: DiscoursePlaybackStore.Change?) {
        (grid.adapter as? DiscourseAdapter)?.refreshProgress(change)
        (tracks.adapter as? TrackAdapter)?.refreshProgress(change)
        val value = model.state.value as? DiscourseViewModel.State.Detail ?: return
        if (change != null && value.discourse.id !in change.discourseIds) return
        val percent = playbackStore.discourseProgress(value.discourse.id, value.tracks?.size ?: value.discourse.totalTracks)
        detail.findViewById<ProgressBar>(R.id.listening_progress).progress = percent
        detail.findViewById<TextView>(R.id.discourse_detail_title).contentDescription =
            "${value.discourse.title}. ${getString(R.string.discourse_listening_percentage, percent)}"
    }

    private fun showState(text: String?, loading: Boolean = false, action: (() -> Unit)? = null) {
        state.isVisible = true
        progress.isVisible = loading
        message.isVisible = text != null
        message.text = text
        retry.isVisible = action != null
        retry.setOnClickListener { action?.invoke() }
        if (model.state.value !is DiscourseViewModel.State.Detail) {
            grid.isVisible = false
            gridSwipe.isVisible = false
            catalogue.isVisible = false
            detail.isVisible = false
        }
    }

    private fun loadImage(image: ImageView, url: String?) {
        image.setImageDrawable(UiTools.getDefaultAudioDrawable(requireContext()))
        val imageUrl = org.videolan.vlc.discourse.resolveDiscourseUrl(url)
        image.tag = imageUrl
        if (imageUrl.isNullOrBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = HttpImageLoader.downloadBitmap(imageUrl)
            if (image.tag == imageUrl && bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    private inner class DiscourseAdapter(
        items: List<Discourse>,
        private val click: (Discourse) -> Unit
    ) : RecyclerView.Adapter<DiscourseAdapter.Holder>() {
        private var items = items
        private var positions = items.mapIndexed { index, item -> item.id to index }.toMap()
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.discourse_image)
            val title: TextView = view.findViewById(R.id.discourse_title)
            val language: TextView = view.findViewById(R.id.discourse_language)
            val counts: TextView = view.findViewById(R.id.discourse_counts)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            layoutInflater.inflate(R.layout.discourse_card, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.language.text = item.language.replaceFirstChar(Char::uppercase)
            holder.counts.text = getString(R.string.discourse_counts, item.totalTracks, item.totalLikes)
            holder.itemView.contentDescription = listOf(item.title, holder.language.text, holder.counts.text).filter(CharSequence::isNotBlank).joinToString(". ")
            holder.itemView.setOnClickListener { click(item) }
            loadImage(holder.image, item.thumbnailUrl)
            bindProgress(holder, item)
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position) else bindProgress(holder, items[position])
        }
        private fun bindProgress(holder: Holder, item: Discourse) {
            val percent = playbackStore.discourseProgress(item.id, item.totalTracks)
            holder.itemView.findViewById<ProgressBar>(R.id.listening_progress).progress = percent
            holder.itemView.contentDescription = "${item.title}. ${holder.language.text}. ${holder.counts.text}. ${getString(R.string.discourse_listening_percentage, percent)}"
        }
        fun refreshProgress(change: DiscoursePlaybackStore.Change?) {
            if (change == null) notifyItemRangeChanged(0, items.size, "progress")
            else change.discourseIds.forEach { id -> positions[id]?.let { notifyItemChanged(it, "progress") } }
        }
        override fun getItemCount() = items.size

        fun update(items: List<Discourse>) {
            this.items = items
            positions = items.mapIndexed { index, item -> item.id to index }.toMap()
            notifyDataSetChanged()
        }
    }

    private inner class TrackAdapter(
        private val items: List<DiscourseAudio>,
        private val click: (Int) -> Unit
    ) : RecyclerView.Adapter<DiscourseTrackHolder>() {
        private val positions = items.mapIndexed { index, item -> item.id to index }.toMap()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DiscourseTrackHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.discourse_track, parent, false)
        )

        override fun onBindViewHolder(holder: DiscourseTrackHolder, position: Int) {
            val item = items[position]
            holder.number.text = (item.trackNumber ?: position + 1).toString()
            loadImage(holder.image, item.discourseThumbnailUrl)
            holder.title.text = item.title
            holder.meta.text = item.durationSeconds?.let { Tools.millisToString((it * 1000).toLong()) }.orEmpty()
            holder.likes.text = getString(R.string.discourse_likes, item.totalLikes)
            holder.played.isVisible = playbackStore.isPlayed(item.id)
            holder.itemView.contentDescription = "${holder.number.text}. ${item.title}. ${holder.meta.text}. ${holder.likes.text}"
            holder.itemView.setOnClickListener { click(holder.bindingAdapterPosition) }
            holder.download.text = getString(when (downloads.state(item)) {
                DiscourseDownloadState.MISSING, DiscourseDownloadState.FAILED -> R.string.download
                DiscourseDownloadState.DOWNLOADING -> R.string.cancel
                DiscourseDownloadState.DOWNLOADED -> R.string.discourse_remove_download
            })
            bindProgress(holder, item)
            holder.download.setOnClickListener {
                when (downloads.state(item)) {
                    DiscourseDownloadState.MISSING, DiscourseDownloadState.FAILED -> if (!downloads.download(item))
                        Toast.makeText(requireContext(), R.string.discourse_download_failed, Toast.LENGTH_SHORT).show()
                    DiscourseDownloadState.DOWNLOADING -> downloads.cancel(item)
                    DiscourseDownloadState.DOWNLOADED -> downloads.remove(item)
                }
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }

        override fun onBindViewHolder(holder: DiscourseTrackHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position)
            else {
                bindProgress(holder, items[position])
                if ("download" in payloads) holder.download.text = getString(when (downloads.state(items[position])) {
                    DiscourseDownloadState.MISSING, DiscourseDownloadState.FAILED -> R.string.download
                    DiscourseDownloadState.DOWNLOADING -> R.string.cancel
                    DiscourseDownloadState.DOWNLOADED -> R.string.discourse_remove_download
                })
            }
        }
        fun refreshDownloads() = notifyItemRangeChanged(0, items.size, "download")
        private fun bindProgress(holder: DiscourseTrackHolder, item: DiscourseAudio) {
            holder.played.isVisible = playbackStore.isPlayed(item.id)
            val percent = playbackStore.audioProgress(item.id)
            holder.itemView.findViewById<ProgressBar>(R.id.listening_progress).progress = percent
            holder.itemView.contentDescription = "${holder.number.text}. ${item.title}. ${holder.meta.text}. ${holder.likes.text}. ${getString(R.string.discourse_listening_percentage, percent)}"
        }
        fun refreshProgress(change: DiscoursePlaybackStore.Change?) {
            if (change == null) notifyItemRangeChanged(0, items.size, "progress")
            else positions[change.audioId]?.let { notifyItemChanged(it, "progress") }
        }
        override fun getItemCount() = items.size
    }

}

private class DiscourseTrackHolder(view: View) : RecyclerView.ViewHolder(view) {
    val number: TextView = view.findViewById(R.id.discourse_track_number)
    val image: ImageView = view.findViewById(R.id.discourse_track_image)
    val title: TextView = view.findViewById(R.id.discourse_track_title)
    val meta: TextView = view.findViewById(R.id.discourse_track_meta)
    val likes: TextView = view.findViewById(R.id.discourse_track_likes)
    val played: ImageView = view.findViewById(R.id.discourse_track_played)
    val download: Button = view.findViewById(R.id.discourse_track_download)
}
