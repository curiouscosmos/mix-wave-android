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
import android.widget.PopupMenu
import android.text.format.Formatter
import androidx.recyclerview.widget.ConcatAdapter
import org.videolan.resources.util.parcelable
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ActionMode
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.videolan.tools.HttpImageLoader
import org.videolan.medialibrary.Tools
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.discourseMetadataTotals
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseDownloadState
import org.videolan.vlc.discourse.DiscourseDownloadProgress
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
    private lateinit var header: View
    private lateinit var headerAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private var trackAdapter: TrackAdapter? = null
    private val detailOnly get() = arguments?.containsKey(DiscourseDetailActivity.ARG_DISCOURSE) == true
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
            refreshDownloadIndicators()
        }
    }

    override fun getTitle() = getString(if (detailOnly) R.string.app_name else R.string.discourse)

    fun openDiscourse(discourse: Discourse) {
        DiscourseDetailActivity.open(requireContext(), discourse)
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
        languageFilter.adapter = ArrayAdapter(requireContext(), R.layout.discourse_catalogue_filter_item, resources.getStringArray(R.array.discourse_languages)).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sortFilter.adapter = ArrayAdapter(requireContext(), R.layout.discourse_catalogue_filter_item, resources.getStringArray(R.array.discourse_sorts)).also {
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
        if (!detailOnly) {
            languageFilter.onItemSelectedListener = filtersChanged
            sortFilter.onItemSelectedListener = filtersChanged
        }
        detail = view.findViewById(R.id.discourse_detail)
        tracks = view.findViewById(R.id.discourse_tracks)
        tracksSwipe = view.findViewById(R.id.discourse_tracks_swipe)
        state = view.findViewById(R.id.discourse_state)
        progress = view.findViewById(R.id.discourse_progress)
        message = view.findViewById(R.id.discourse_message)
        retry = view.findViewById(R.id.discourse_retry)
        grid.layoutManager = LinearLayoutManager(requireContext())
        tracks.layoutManager = LinearLayoutManager(requireContext())
        detail.setBackgroundResource(R.drawable.discourse_detail_background)
        header = layoutInflater.inflate(R.layout.discourse_detail_header, tracks, false)
        tracks.clipToPadding = false
        tracks.itemAnimator = null
        trackAdapter = null
        headerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            init { stateRestorationPolicy = StateRestorationPolicy.PREVENT }
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(header) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        tracks.adapter = ConcatAdapter(headerAdapter)
        gridSwipe.setOnRefreshListener { model.refresh() }
        tracksSwipe.setOnRefreshListener { model.retryDetail() }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) model.loadMore()
            }
        })
        header.findViewById<ImageButton>(R.id.discourse_back).setOnClickListener { model.back() }
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = model.back()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        if (detailOnly && model.state.value is DiscourseViewModel.State.Idle) {
            arguments?.parcelable<Discourse>(DiscourseDetailActivity.ARG_DISCOURSE)?.let(model::openDiscourse)
        }
        model.state.observe(viewLifecycleOwner, ::render)
        viewLifecycleOwner.lifecycleScope.launch {
            playbackStore.initialize()
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    while (isActive) {
                        refreshDownloadIndicators()
                        delay(500)
                    }
                }
                refreshProgress(null)
                playbackStore.changes.collect { refreshProgress(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!detailOnly) model.load()
        (grid.adapter as? DiscourseAdapter)?.refreshDownloads()
        trackAdapter?.refreshDownloads()
        refreshDownloadSummary()
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
                        ?: run { grid.adapter = DiscourseAdapter(emptyList(), ::openDiscourse) }
                } else {
                    state.isVisible = false
                    catalogueEmpty.isVisible = false
                    gridSwipe.isVisible = true
                    grid.isVisible = true
                    (grid.adapter as? DiscourseAdapter)?.update(value.discourses)
                        ?: run { grid.adapter = DiscourseAdapter(value.discourses, ::openDiscourse) }
                }
            }
            is DiscourseViewModel.State.Detail -> {
                backCallback.isEnabled = !detailOnly
                catalogue.isVisible = false
                grid.isVisible = false
                detail.isVisible = true
                value.tracks?.let { audios ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { playbackStore.register(audios, value.discourse.id) }
                        refreshProgress(null)
                    }
                }
                val image = header.findViewById<ImageView>(R.id.discourse_detail_image)
                header.findViewById<TextView>(R.id.discourse_detail_title).text = value.discourse.title
                header.findViewById<TextView>(R.id.discourse_download_count).isVisible = false
                header.findViewById<Button>(R.id.discourse_cancel_downloads).isVisible = false
                header.findViewById<ProgressBar>(R.id.discourse_download_progress).isVisible = false
                loadImage(image, value.discourse.thumbnailUrl)
                val audios = value.tracks.orEmpty()
                if (value.tracks != null || value.error) headerAdapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.ALLOW
                header.findViewById<TextView>(R.id.discourse_tracks_heading).text = getString(R.string.discourse_tracks_heading, value.tracks?.size ?: value.discourse.totalTracks)
                header.findViewById<TextView>(R.id.discourse_detail_meta).text = detailMetadata(audios, value.tracks?.size ?: value.discourse.totalTracks)
                header.findViewById<Button>(R.id.discourse_play_all).apply {
                    isEnabled = audios.isNotEmpty()
                    setOnClickListener {
                        model.recordRecentlyPlayed(value.discourse)
                        requireContext().playDiscourseAudios(audios, 0)
                    }
                }
                header.findViewById<Button>(R.id.discourse_download_all).isEnabled = audios.isNotEmpty()
                header.findViewById<Button>(R.id.discourse_remove_all).isVisible = false
                refreshProgress(null)
                when {
                    value.error -> showState(getString(R.string.discourse_tracks_failed)) { model.retryDetail() }
                    value.tracks == null -> showState(null, loading = true)
                    value.tracks.isEmpty() -> showState(getString(R.string.discourse_no_tracks))
                    else -> {
                        state.isVisible = false
                        header.findViewById<Button>(R.id.discourse_download_all).setOnClickListener {
                            val results = value.tracks.filter { shouldEnqueue(downloads.state(it)) }.map { downloads.download(it) }
                            if (results.any { !it }) {
                                Toast.makeText(requireContext(), R.string.discourse_download_failed, Toast.LENGTH_SHORT).show()
                            }
                            tracks.adapter?.notifyDataSetChanged()
                            refreshDownloadSummary()
                        }
                        header.findViewById<Button>(R.id.discourse_remove_all).setOnClickListener {
                            AlertDialog.Builder(requireContext())
                                .setMessage(R.string.discourse_remove_all_confirm)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.discourse_remove_download) { _, _ ->
                                    value.tracks.forEach(downloads::remove)
                                    tracks.adapter?.notifyDataSetChanged()
                                    refreshDownloadSummary()
                                }.show()
                        }
                        header.findViewById<Button>(R.id.discourse_cancel_downloads).setOnClickListener {
                            value.tracks.filter { downloads.state(it) == DiscourseDownloadState.DOWNLOADING }.forEach(downloads::cancel)
                            tracks.adapter?.notifyDataSetChanged()
                            refreshDownloadSummary()
                        }
                        val concat = tracks.adapter as ConcatAdapter
                        trackAdapter?.let(concat::removeAdapter)
                        trackAdapter = TrackAdapter(value.tracks) { position ->
                            model.recordRecentlyPlayed(value.discourse)
                            requireContext().playDiscourseAudios(value.tracks, position)
                        }
                        concat.addAdapter(trackAdapter!!)
                        refreshDownloadSummary()
                    }
                }
            }
        }
    }

    private fun refreshProgress(change: DiscoursePlaybackStore.Change?) {
        (grid.adapter as? DiscourseAdapter)?.refreshProgress(change)
        trackAdapter?.refreshProgress(change)
        val value = model.state.value as? DiscourseViewModel.State.Detail ?: return
        if (change != null && value.discourse.id !in change.discourseIds) return
        val percent = playbackStore.discourseProgress(value.discourse.id, value.tracks?.size ?: value.discourse.totalTracks)
        header.findViewById<ProgressBar>(R.id.listening_progress).progress = percent
        updateDetailTitleContentDescription(value, percent)
    }

    private fun refreshDownloadSummary() {
        val value = model.state.value as? DiscourseViewModel.State.Detail ?: return
        val audios = value.tracks ?: return
        val downloaded = audios.count { downloads.state(it) == DiscourseDownloadState.DOWNLOADED }
        val active = audios.count { downloads.state(it) == DiscourseDownloadState.DOWNLOADING }
        header.findViewById<Button>(R.id.discourse_download_all).isEnabled = active == 0 && downloaded < audios.size
        header.findViewById<Button>(R.id.discourse_remove_all).isVisible = downloaded > 0
        header.findViewById<Button>(R.id.discourse_cancel_downloads).isVisible = active > 0
        header.findViewById<ImageView>(R.id.discourse_detail_download_icon).setColorFilter(
            ContextCompat.getColor(requireContext(), if (downloaded > 0) R.color.red else R.color.grey500)
        )
        header.findViewById<TextView>(R.id.discourse_detail_downloaded_count).apply {
            isVisible = downloaded > 0
            text = downloaded.toString()
        }
        val progress = header.findViewById<ProgressBar>(R.id.discourse_download_progress)
        header.findViewById<TextView>(R.id.discourse_download_count).apply {
            isVisible = audios.isNotEmpty()
            text = getString(R.string.discourse_download_count, downloaded, audios.size)
        }
        bindDownloadProgress(progress, if (audios.isNotEmpty()) {
            DiscourseDownloadProgress((downloaded * 100 / audios.size).coerceAtMost(100),
                when {
                    downloaded == audios.size -> DiscourseDownloadState.DOWNLOADED
                    active > 0 -> DiscourseDownloadState.DOWNLOADING
                    else -> DiscourseDownloadState.MISSING
                })
        } else DiscourseDownloadProgress(null, DiscourseDownloadState.MISSING), active > 0)
        updateDetailTitleContentDescription(value, playbackStore.discourseProgress(value.discourse.id, audios.size))
    }

    private fun updateDetailTitleContentDescription(value: DiscourseViewModel.State.Detail, percent: Int) {
        val downloaded = value.tracks?.count { downloads.state(it) == DiscourseDownloadState.DOWNLOADED } ?: 0
        header.findViewById<TextView>(R.id.discourse_detail_title).contentDescription = listOf(
            value.discourse.title,
            downloaded.takeIf { it > 0 }?.let { getString(R.string.discourse_download_count, it, value.tracks?.size ?: value.discourse.totalTracks) },
            getString(R.string.discourse_listening_percentage, percent)
        ).filterNotNull().joinToString(". ")
    }

    private fun refreshDownloadIndicators() {
        (grid.adapter as? DiscourseAdapter)?.refreshDownloads()
        trackAdapter?.refreshDownloads()
        refreshDownloadSummary()
    }

    private fun bindDownloadProgress(view: ProgressBar, value: DiscourseDownloadProgress, visible: Boolean = true) {
        val downloading = visible && value.state == DiscourseDownloadState.DOWNLOADING
        view.isVisible = downloading
        view.isIndeterminate = downloading && value.percent == null
        view.progress = if (downloading) value.percent ?: 0 else 0
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

    private fun detailMetadata(audios: List<DiscourseAudio>, count: Int): String {
        val totals = discourseMetadataTotals(audios)
        fun known(text: String, complete: Boolean) = if (complete) text else getString(R.string.discourse_known_total, text)
        return listOfNotNull(
            getString(R.string.discourse_track_count, count),
            totals.durationSeconds?.let { known(Tools.millisToString((it * 1000).toLong()), totals.durationComplete) },
            totals.fileSize?.let { known(Formatter.formatFileSize(requireContext(), it), totals.sizeComplete) }
        ).joinToString(" · ")
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
            val card: View = view.findViewById(R.id.discourse_catalogue_card)
            val heading: View = view.findViewById(R.id.discourse_letter_heading)
            val letter: TextView = view.findViewById(R.id.discourse_letter)
            val image: ImageView = view.findViewById(R.id.discourse_image)
            val title: TextView = view.findViewById(R.id.discourse_title)
            val language: TextView = view.findViewById(R.id.discourse_language)
            val counts: TextView = view.findViewById(R.id.discourse_counts)
            val downloadIcon: ImageView = view.findViewById(R.id.discourse_download_icon)
            val downloadedCount: TextView = view.findViewById(R.id.discourse_downloaded_count)
            val downloaded: ProgressBar = view.findViewById(R.id.discourse_download_progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            layoutInflater.inflate(R.layout.discourse_catalogue_card, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val heading = discourseCatalogueHeading(item.title, items.getOrNull(position - 1)?.title,
                model.sortFilter == DiscourseViewModel.SortFilter.DEFAULT)
            holder.heading.isVisible = heading != null
            holder.letter.text = heading
            holder.title.text = item.title
            holder.language.text = item.language.replaceFirstChar(Char::uppercase)
            holder.counts.text = getString(R.string.discourse_counts, item.totalTracks, item.totalLikes)
            bindDiscourseDownloadIndicator(holder, item)
            bindDiscourseDownloadProgress(holder.downloaded, item)
            holder.card.setOnClickListener { click(item) }
            loadImage(holder.image, item.thumbnailUrl)
            bindProgress(holder, item)
        }

        private fun bindProgress(holder: Holder, item: Discourse) {
            val percent = playbackStore.discourseProgress(item.id, item.totalTracks)
            holder.itemView.findViewById<ProgressBar>(R.id.listening_progress).progress = percent
            val completed = downloads.downloadedCount(item.id)
            holder.card.contentDescription = listOf(item.title, holder.language.text, holder.counts.text,
                completed.takeIf { it > 0 }?.let { getString(R.string.discourse_download_count, it, item.totalTracks) },
                getString(R.string.discourse_listening_percentage, percent)
            ).filterNotNull().filter(CharSequence::isNotBlank).joinToString(". ")
        }
        fun refreshProgress(change: DiscoursePlaybackStore.Change?) {
            if (change == null) notifyItemRangeChanged(0, items.size, "progress")
            else change.discourseIds.forEach { id -> positions[id]?.let { notifyItemChanged(it, "progress") } }
        }
        fun refreshDownloads() = notifyItemRangeChanged(0, items.size, "download")
        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position)
            else if ("download" in payloads) {
                val item = items[position]
                bindDiscourseDownloadIndicator(holder, item)
                bindDiscourseDownloadProgress(holder.downloaded, item)
                bindProgress(holder, item)
            }
            else bindProgress(holder, items[position])
        }

        fun update(items: List<Discourse>) {
            this.items = items
            positions = items.mapIndexed { index, item -> item.id to index }.toMap()
            notifyDataSetChanged()
        }

        private fun bindDiscourseDownloadProgress(view: ProgressBar, item: Discourse) {
            val completed = downloads.downloadedCount(item.id)
            val active = downloads.activeDownloadCount(item.id)
            bindDownloadProgress(view, DiscourseDownloadProgress(
                (completed * 100 / item.totalTracks.coerceAtLeast(1)).coerceAtMost(100),
                when {
                    item.totalTracks > 0 && completed >= item.totalTracks -> DiscourseDownloadState.DOWNLOADED
                    active > 0 -> DiscourseDownloadState.DOWNLOADING
                    else -> DiscourseDownloadState.MISSING
                }
            ), active > 0)
        }

        private fun bindDiscourseDownloadIndicator(holder: Holder, item: Discourse) {
            val completed = downloads.downloadedCount(item.id)
            holder.downloadIcon.setColorFilter(ContextCompat.getColor(requireContext(), if (completed > 0) R.color.green500 else R.color.grey500))
            holder.downloadedCount.isVisible = completed > 0
            holder.downloadedCount.text = completed.toString()
        }
    }

    private inner class TrackAdapter(
        private val items: List<DiscourseAudio>,
        private val click: (Int) -> Unit
    ) : RecyclerView.Adapter<DiscourseTrackHolder>() {
        private val positions = items.mapIndexed { index, item -> item.id to index }.toMap()
        init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DiscourseTrackHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.discourse_track, parent, false)
        )

        override fun onBindViewHolder(holder: DiscourseTrackHolder, position: Int) {
            val item = items[position]
            holder.number.text = (item.trackNumber ?: position + 1).toString()
            loadImage(holder.image, item.discourseThumbnailUrl)
            holder.title.text = item.title
            holder.meta.text = listOfNotNull(
                item.durationSeconds?.takeIf { it.isFinite() && it > 0 }?.let { Tools.millisToString((it * 1000).toLong()) },
                item.fileSize?.takeIf { it > 0 }?.let { Formatter.formatFileSize(requireContext(), it) }
            ).joinToString(" · ")
            holder.likes.isVisible = false
            holder.played.isVisible = playbackStore.isPlayed(item.id)
            holder.itemView.contentDescription = "${holder.number.text}. ${item.title}. ${holder.meta.text}"
            holder.itemView.setOnClickListener { holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(click) }
            bindDownloadAction(holder, item)
            bindProgress(holder, item)
            bindDownloadProgress(holder.downloadProgress, downloads.progress(item))
            holder.download.setOnClickListener {
                val menu = PopupMenu(requireContext(), holder.download)
                val downloadState = downloads.state(item)
                menu.menu.add(0, 1, 0, getString(when (downloadState) {
                    DiscourseDownloadState.MISSING, DiscourseDownloadState.FAILED -> R.string.download
                    DiscourseDownloadState.DOWNLOADING -> R.string.cancel
                    DiscourseDownloadState.DOWNLOADED -> R.string.discourse_remove_download
                }))
                menu.menu.add(0, 2, 1, getString(if (playbackStore.isPlayed(item.id)) R.string.mark_as_not_played else R.string.mark_as_played))
                menu.setOnMenuItemClickListener { action ->
                    if (action.itemId == 1) {
                        when (downloads.state(item)) {
                            DiscourseDownloadState.MISSING, DiscourseDownloadState.FAILED -> if (!downloads.download(item))
                                Toast.makeText(requireContext(), R.string.discourse_download_failed, Toast.LENGTH_SHORT).show()
                            DiscourseDownloadState.DOWNLOADING -> downloads.cancel(item)
                            DiscourseDownloadState.DOWNLOADED -> downloads.remove(item)
                        }
                    } else {
                        if (playbackStore.isPlayed(item.id)) playbackStore.clearPlayed(item.id) else playbackStore.markPlayed(item.id)
                    }
                    val index = holder.bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) notifyItemChanged(index)
                    refreshDownloadSummary()
                    true
                }
                menu.show()
            }
        }

        override fun onBindViewHolder(holder: DiscourseTrackHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position)
            else {
                bindProgress(holder, items[position])
                bindDownloadProgress(holder.downloadProgress, downloads.progress(items[position]))
                if ("download" in payloads) bindDownloadAction(holder, items[position])
            }
        }
        fun refreshDownloads() = notifyItemRangeChanged(0, items.size, "download")
        private fun bindProgress(holder: DiscourseTrackHolder, item: DiscourseAudio) {
            holder.played.isVisible = playbackStore.isPlayed(item.id)
            val percent = playbackStore.audioProgress(item.id)
            holder.itemView.findViewById<ProgressBar>(R.id.listening_progress).progress = percent
            val downloadState = if (downloads.state(item) == DiscourseDownloadState.DOWNLOADED) R.string.discourse_downloaded else R.string.not_downloaded
            holder.itemView.contentDescription = "${holder.number.text}. ${item.title}. ${holder.meta.text}. ${getString(downloadState)}. ${getString(R.string.discourse_listening_percentage, percent)}"
        }
        private fun bindDownloadAction(holder: DiscourseTrackHolder, item: DiscourseAudio) {
            val state = downloads.state(item)
            holder.download.contentDescription = getString(R.string.more_actions)
            holder.itemView.setBackgroundResource(if (state == DiscourseDownloadState.DOWNLOADED) R.drawable.discourse_track_card_downloaded else R.drawable.discourse_track_card)
            holder.itemView.findViewById<ImageView>(R.id.discourse_track_downloaded).isVisible = state == DiscourseDownloadState.DOWNLOADED
            val value = downloads.progress(item)
            holder.itemView.findViewById<ProgressBar>(R.id.discourse_track_spinner).apply {
                val indeterminate = value.percent == null
                if (isIndeterminate != indeterminate) {
                    isVisible = false
                    isIndeterminate = indeterminate
                }
                progress = value.percent ?: 0
                isVisible = state == DiscourseDownloadState.DOWNLOADING
            }
            holder.likes.apply {
                isVisible = state == DiscourseDownloadState.DOWNLOADING || state == DiscourseDownloadState.FAILED
                text = when {
                    state == DiscourseDownloadState.FAILED -> getString(R.string.discourse_download_failed)
                    value.percent != null -> getString(R.string.discourse_downloading_percent, value.percent)
                    else -> getString(R.string.discourse_downloading)
                }
            }
        }
        fun refreshProgress(change: DiscoursePlaybackStore.Change?) {
            if (change == null) notifyItemRangeChanged(0, items.size, "progress")
            else positions[change.audioId]?.let { notifyItemChanged(it, "progress") }
        }
        override fun getItemCount() = items.size
    }

}

internal fun discourseCatalogueHeading(title: String, previousTitle: String?, enabled: Boolean): String? {
    fun initial(value: String) = value.trimStart().firstOrNull()?.takeIf(Char::isLetter)?.uppercaseChar()?.toString() ?: "#"
    if (!enabled) return null
    val letter = initial(title)
    return letter.takeIf { previousTitle == null || initial(previousTitle) != letter }
}

private class DiscourseTrackHolder(view: View) : RecyclerView.ViewHolder(view) {
    val number: TextView = view.findViewById(R.id.discourse_track_number)
    val image: ImageView = view.findViewById(R.id.discourse_track_image)
    val title: TextView = view.findViewById(R.id.discourse_track_title)
    val meta: TextView = view.findViewById(R.id.discourse_track_meta)
    val likes: TextView = view.findViewById(R.id.discourse_track_likes)
    val played: ImageView = view.findViewById(R.id.discourse_track_played)
    val download: ImageButton = view.findViewById(R.id.discourse_track_download)
    val downloadProgress: ProgressBar = view.findViewById(R.id.discourse_download_progress)
}
