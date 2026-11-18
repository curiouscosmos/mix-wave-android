package org.videolan.vlc.gui.audio

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.LinearLayout
import org.videolan.medialibrary.Tools
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.videolan.vlc.discourse.DiscoursePlaybackStore
import org.videolan.vlc.discourse.discoursePlaybackIds
import org.videolan.vlc.discourse.discourseAudioSnapshot
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.tools.HttpImageLoader
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import org.videolan.vlc.viewmodels.HomeViewModel
import org.videolan.vlc.viewmodels.HomeTrack
import kotlinx.coroutines.currentCoroutineContext
import org.videolan.vlc.util.ThumbnailsProvider
import org.videolan.vlc.gui.view.SwipeRefreshLayout
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseDownloadStore
import org.videolan.vlc.discourse.playDiscourseAudio
import org.videolan.vlc.discourse.resolveDiscourseUrl
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.getAudioIconDrawable
import org.videolan.vlc.media.MediaUtils

class RecentlyPlayedDiscoursesFragment : Fragment(R.layout.recently_played_discourses) {
    private val model: HomeViewModel by viewModels()
    private lateinit var rows: HomeAdapter
    private val imageJobs = HashMap<View, Job>()
    private val horizontalStates = HashMap<String, android.os.Parcelable?>()
    private lateinit var likesUi: DiscourseLikesUi
    private val progressCards = HashMap<View, Pair<String, Int?>>()
    private val audioCards = HashMap<String, MutableSet<View>>()
    private val discourseCards = HashMap<String, MutableSet<View>>()
    private val playbackStore by lazy { DiscoursePlaybackStore(requireContext()) }
    private val downloads by lazy { DiscourseDownloadStore(requireContext()) }
    private fun bindProgress(card: View, id: String, count: Int?) {
        progressCards[card] = id to count
        (if (count == null) audioCards else discourseCards).getOrPut(id) { HashSet() }.add(card)
        updateProgress(card, id, count)
    }
    private fun updateProgress(card: View, id: String, count: Int?) {
        val percent = if (count == null) playbackStore.audioProgress(id) else playbackStore.discourseProgress(id, count)
        card.findViewById<ProgressBar>(R.id.listening_progress).apply {
            if (count == null) bindTrackProgress(percent) else progress = percent
        }
        val titleId = if (count == null) R.id.discourse_track_title else R.id.discourse_title
        if (count == null) card.findViewById<View>(R.id.discourse_track_played).isVisible = playbackStore.isPlayed(id)
        val accessibleCard = if (count == null) card else card.findViewById<View>(R.id.discourse_catalogue_card)
        accessibleCard.contentDescription = "${card.findViewById<TextView>(titleId).text}. ${getString(R.string.discourse_listening_percentage, percent)}"
        if (count != null) {
            val metadata = listOf(R.id.discourse_language, R.id.discourse_location, R.id.discourse_counts, R.id.discourse_date)
                .map { card.findViewById<TextView>(it).text }.filter(CharSequence::isNotBlank).joinToString(". ")
            accessibleCard.contentDescription = "${accessibleCard.contentDescription}. $metadata"
        }
        (card.getTag(R.id.continue_listening_label) as? MediaWrapper)?.let { updateContinueListening(card, it) }
    }
    private fun unbind(card: View) {
        imageJobs.remove(card)?.cancel()
        likesUi.unbind(card)
        card.setTag(R.id.continue_listening_label, null)
        progressCards.remove(card)?.let { (id, count) ->
            val index = if (count == null) audioCards else discourseCards
            index[id]?.let { cards -> if (cards.remove(card) && cards.isEmpty()) index.remove(id) }
        }
    }
    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.home_rows)?.adapter = null
        imageJobs.values.forEach { it.cancel() }
        imageJobs.clear()
        progressCards.clear()
        audioCards.clear()
        discourseCards.clear()
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        likesUi = DiscourseLikesUi(requireContext(), viewLifecycleOwner)
        rows = HomeAdapter()
        view.findViewById<RecyclerView>(R.id.home_rows).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rows
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val hero = recyclerView.layoutManager?.findViewByPosition(0)
                    (activity as? org.videolan.vlc.gui.MainActivity)?.syncHomeToolbar(hero?.top)
                }
            })
        }
        renderRows()
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.all_stats_swipe)
        swipe.setOnRefreshListener { model.refreshHistory(); model.loadWeekly(true) }
        model.refreshing.observe(viewLifecycleOwner) { swipe.isRefreshing = it }
        model.recentDiscourses.observe(viewLifecycleOwner) { renderRows() }
        model.recentTracks.observe(viewLifecycleOwner) { renderRows() }
        model.weeklyDiscourses.observe(viewLifecycleOwner) { renderRows() }
        model.weeklyTracks.observe(viewLifecycleOwner) { renderRows() }
        viewLifecycleOwner.lifecycleScope.launch {
            playbackStore.initialize()
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    downloads.summaryChanges().collect { changed ->
                        changed.forEach { id ->
                            discourseCards[id]?.forEach { card ->
                                bindDownloadSummary(card, id, progressCards.getValue(card).second ?: 0)
                            }
                        }
                        val audios = model.recentTracks.value.orEmpty().mapNotNull { it.audio } + model.weeklyTracks.value.orEmpty()
                        audios.filter { it.discourseId in changed }.forEach { audio ->
                            audioCards[audio.id]?.forEach { card ->
                                val meta = card.findViewById<TextView>(R.id.discourse_track_meta)
                                (meta.tag as? TrackCardStats)?.let {
                                    meta.bindTrackStats(it.copy(downloaded = downloads.isAudioDownloaded(audio.id)))
                                }
                            }
                        }
                    }
                }
                progressCards.forEach { (card, item) -> updateProgress(card, item.first, item.second) }
                playbackStore.changes.collect { change ->
                    audioCards[change.audioId]?.forEach { updateProgress(it, change.audioId, null) }
                    change.discourseIds.forEach { id ->
                        discourseCards[id]?.forEach { card ->
                            updateProgress(card, id, progressCards.getValue(card).second)
                        }
                    }
                }
            }
        }
    }

    private data class HomeRow(
        val key: String, val kind: Int, val title: String = "", val metadata: String = "",
        val discourse: Discourse? = null, val audio: DiscourseAudio? = null,
        val track: HomeTrack? = null, val children: List<HomeRow> = emptyList()
    ) {
        fun sameContent(other: HomeRow): Boolean {
            if ((discourse == null) != (other.discourse == null) || (audio == null) != (other.audio == null) ||
                (track == null) != (other.track == null)) return false
            if (title != other.title || metadata != other.metadata) return false
            if (discourse != null && (other.discourse == null || !sameDiscourseCard(discourse, other.discourse))) return false
            if (audio != null && (other.audio == null || audio.title != other.audio.title ||
                    audio.discourseThumbnailUrl != other.audio.discourseThumbnailUrl ||
                    audio.totalLikes != other.audio.totalLikes || audio.plays != other.audio.plays)) return false
            if (track != null && (other.track == null || track.key != other.track.key || track.playbackTime != other.track.playbackTime || track.title != other.track.title ||
                    track.subtitle != other.track.subtitle || track.artwork != other.track.artwork ||
                    track.audio?.discourseThumbnailUrl != other.track.audio?.discourseThumbnailUrl ||
                    track.audio?.totalLikes != other.track.audio?.totalLikes || track.audio?.plays != other.track.audio?.plays)) return false
            return children.size == other.children.size && children.zip(other.children).all { (old, new) ->
                old.key == new.key && old.kind == new.kind && old.sameContent(new)
            }
        }
    }

    private fun renderRows() {
        val result = ArrayList<HomeRow>()
        result += HomeRow("hero", 4)
        val recentTracks = model.recentTracks.value.orEmpty()
        recentTracks.firstOrNull()?.let {
            result += HomeRow("continue_listening", 5, track = it)
        }
        fun heading(key: String, label: Int) {
            result += HomeRow(key, 0, getString(label), if (result.isEmpty()) "first" else "")
        }
        fun discourse(item: Discourse) = HomeRow(item.id, 1, discourse = item,
            metadata = getString(R.string.discourse_track_count, item.totalTracks))
        model.recentDiscourses.value.orEmpty().takeIf { it.isNotEmpty() }?.let { items ->
            heading("recent_discourses", R.string.recently_played_discourses)
            result += items.map { discourse(it) }
        }
        recentTracks.drop(1).takeIf { it.isNotEmpty() }?.let { items ->
            heading("recent_tracks", R.string.recently_played_tracks)
            result += HomeRow("recent_tracks_list", 3, children = items.map { HomeRow(it.key, 2, track = it) })
        }
        model.weeklyDiscourses.value.orEmpty().takeIf { it.isNotEmpty() }?.let { items ->
            heading("weekly_discourses", R.string.top_discourses_week)
            result += HomeRow("weekly_discourses_list", 3, children = items.map { discourse(it) })
        }
        model.weeklyTracks.value.orEmpty().takeIf { it.isNotEmpty() }?.let { items ->
            heading("weekly_tracks", R.string.top_tracks_week)
            result += items.map { HomeRow(it.id, 2, audio = it) }
        }
        rows.submitList(result)
    }

    private inner class HomeAdapter(private val horizontal: Boolean = false) :
        ListAdapter<HomeRow, HomeHolder>(object : DiffUtil.ItemCallback<HomeRow>() {
            override fun areItemsTheSame(old: HomeRow, new: HomeRow) = old.key == new.key && old.kind == new.kind
            override fun areContentsTheSame(old: HomeRow, new: HomeRow) = old.sameContent(new)
        }) {
        init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        override fun getItemViewType(position: Int) = getItem(position).kind
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeHolder {
            val card = when (viewType) {
                4 -> layoutInflater.inflate(R.layout.home_hero, parent, false)
                5 -> createTrackRow(parent).apply {
                    (layoutParams as RecyclerView.LayoutParams).topMargin = -dp(24)
                    minimumHeight = dp(128)
                    val text = findViewById<LinearLayout>(R.id.discourse_track_text)
                    text.addView(TextView(context).apply {
                        id = R.id.continue_listening_label
                        setText(R.string.continue_listening)
                        setTextColor(android.graphics.Color.rgb(245, 217, 179))
                        textSize = 11f
                        letterSpacing = 0.12f
                        setPadding(0, 0, 0, dp(8))
                    }, 0)
                    val time = findViewById<TextView>(R.id.discourse_track_meta)
                    text.removeView(time)
                    text.addView(time)
                    findViewById<TextView>(R.id.discourse_track_title).apply {
                        setPaddingRelative(0, 0, 0, 0)
                        textSize = 20f
                    }
                }
                0 -> TextView(parent.context).apply {
                    val appearance = android.util.TypedValue()
                    context.theme.resolveAttribute(R.attr.textAppearanceHeadline6, appearance, true)
                    setTextAppearance(appearance.resourceId)
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }
                1 -> layoutInflater.inflate(R.layout.discourse_catalogue_card, parent, false).apply {
                    layoutParams = RecyclerView.LayoutParams(if (horizontal) dp(420) else -1, -2).apply {
                        marginStart = dp(if (horizontal) 0 else 10)
                        marginEnd = dp(if (horizontal) 8 else 10)
                    }
                    if (horizontal) findViewById<TextView>(R.id.discourse_title).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }
                }
                2 -> createTrackRow(parent).apply {
                    layoutParams.width = if (horizontal) dp(412.5f) else -1
                }
                else -> RecyclerView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                    layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                    adapter = HomeAdapter(true)
                    itemAnimator = null
                }
            }
            return HomeHolder(card)
        }
        override fun onBindViewHolder(holder: HomeHolder, position: Int) {
            val row = getItem(position)
            val card = holder.itemView
            if (row.kind == 4) {
                val image = card.findViewById<ImageView>(R.id.home_hero_image)
                imageJobs.remove(card)?.cancel()
                if (image.drawable == null) imageJobs[card] = viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        image.context.assets.open("images/images/osho.jpg").use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }
                    image.setImageBitmap(bitmap)
                }
                return
            }
            if (row.kind == 3) {
                val list = card as RecyclerView
                holder.key?.let { horizontalStates[it] = list.layoutManager?.onSaveInstanceState() }
                holder.key = row.key
                val padding = if (row.children.firstOrNull()?.kind == 1) dp(10) else 0
                list.setPadding(padding, 0, padding, 0)
                if (list.adapter == null) list.adapter = HomeAdapter(true)
                (list.adapter as HomeAdapter).submitList(row.children) {
                    list.layoutManager?.onRestoreInstanceState(horizontalStates[row.key])
                }
                return
            }
            unbind(card)
            when (row.kind) {
                0 -> (card as TextView).apply {
                    text = row.title
                    setPadding(dp(16), if (row.metadata == "first") 0 else dp(24), dp(16), dp(8))
                }
                1 -> bindDiscourseCard(card, row.discourse!!, row.metadata)
                5 -> {
                    val snapshot = row.track!!
                    bindTrackCard(card, snapshot)
                    likesUi.unbind(card)
                    card.findViewById<TextView>(R.id.discourse_track_meta).tag = null
                    card.setTag(R.id.continue_listening_label, snapshot.media)
                    updateContinueListening(card, snapshot.media)
                    card.setOnClickListener {
                        val audio = snapshot.audio
                        if (audio != null) requireContext().playDiscourseAudio(audio)
                        else MediaUtils.openMedia(requireContext(), snapshot.media)
                    }
                }
                2 -> if (row.track != null) bindTrackCard(card, row.track) else {
                    val audio = row.audio!!
                    loadStatsImage(card, card.findViewById(R.id.discourse_track_image), audio.discourseThumbnailUrl)
                    card.findViewById<TextView>(R.id.discourse_track_title).text = audio.title
                    card.findViewById<TextView>(R.id.discourse_track_meta).bindTrackStats(
                        TrackCardStats(audio.plays.toLong(), audio.totalLikes, downloads.isAudioDownloaded(audio.id)))
                    card.setOnClickListener { requireContext().playDiscourseAudio(audio) }
                    likesUi.bind(card, audio.id, true, audio.title, audio.totalLikes, audioItem = audio)
                    bindProgress(card, audio.id, null)
                }
            }
        }
        override fun onViewRecycled(holder: HomeHolder) {
            if (holder.itemView is RecyclerView) {
                val list = holder.itemView as RecyclerView
                holder.key?.let { horizontalStates[it] = list.layoutManager?.onSaveInstanceState() }
                list.adapter = null
                holder.key = null
            } else unbind(holder.itemView)
        }
    }
    private class HomeHolder(view: View) : RecyclerView.ViewHolder(view) { var key: String? = null }
    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private fun updateContinueListening(card: View, track: MediaWrapper) {
        val position = track.discoursePlaybackIds()?.let { playbackStore.position(it.audioId) } ?: track.time
        card.findViewById<ProgressBar>(R.id.listening_progress).bindTrackProgress(trackListeningProgress(position, track.length))
        val time = getString(R.string.continue_listening_time,
            Tools.millisToString(position.coerceAtLeast(0)), Tools.millisToString(track.length.coerceAtLeast(0)))
        card.findViewById<TextView>(R.id.discourse_track_meta).text = time
        card.findViewById<View>(R.id.discourse_track_played).isVisible = false
        card.contentDescription = "${getString(R.string.continue_listening)}. ${track.title}. $time"
    }

    private fun bindTrackCard(card: View, snapshot: HomeTrack) {
        val track = snapshot.media
        val image = card.findViewById<ImageView>(R.id.discourse_track_image)
        image.setImageDrawable(getAudioIconDrawable(requireContext(), MediaLibraryItem.TYPE_MEDIA, true))
        val thumbnail = snapshot.audio?.discourseThumbnailUrl
        if (!thumbnail.isNullOrBlank()) loadStatsImage(card, image, thumbnail)
        else imageJobs[card] = viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = ThumbnailsProvider.obtainBitmap(track, dp(88))
            currentCoroutineContext().ensureActive()
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
        card.findViewById<TextView>(R.id.discourse_track_title).text = snapshot.title
        card.findViewById<TextView>(R.id.discourse_track_meta).bindTrackStats(
            TrackCardStats(snapshot.audio?.plays?.toLong() ?: track.playCount, snapshot.audio?.totalLikes ?: 0,
                snapshot.audio?.let { downloads.isAudioDownloaded(it.id) } ?: (track.uri.scheme in listOf("file", "content"))))
        card.setOnClickListener { MediaUtils.openMedia(requireContext(), track) }
        track.discoursePlaybackIds()?.let {
            bindProgress(card, it.audioId, null)
            val audio = snapshot.audio
            likesUi.bind(card, it.audioId, true, track.title.orEmpty(), audio?.totalLikes ?: 0,
                audioItem = audio ?: track.discourseAudioSnapshot())
        }
        if (track.discoursePlaybackIds() == null) {
            card.findViewById<TextView>(R.id.discourse_like).apply {
                isVisible = true
                bindLocalTrackFavourite(track, viewLifecycleOwner)
            }
            card.findViewById<ProgressBar>(R.id.listening_progress).bindTrackProgress(trackListeningProgress(track.time, track.length))
            card.findViewById<View>(R.id.discourse_track_played).isVisible = false
            card.contentDescription = "${snapshot.title}. ${snapshot.subtitle}"
        }
    }

    private fun createTrackRow(container: ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.discourse_track, container, false)
        row.findViewById<View>(R.id.discourse_track_play).setOnClickListener { row.performClick() }
        row.clipToOutline = true
        row.findViewById<View>(R.id.discourse_track_number).isVisible = false
        row.findViewById<View>(R.id.discourse_track_download).isVisible = false
        return row
    }

    private fun bindDiscourseCard(card: View, discourse: Discourse, metadata: String) {
        card.findViewById<View>(R.id.discourse_letter_heading).isVisible = false
        card.findViewById<TextView>(R.id.discourse_title).text = discourse.title
        card.bindDiscourseCardMetadata(discourse, metadata)
        likesUi.bind(card, discourse.id, false, discourse.title, discourse.totalLikes, discourse = discourse)
        bindDownloadSummary(card, discourse.id, discourse.totalTracks)
        loadStatsImage(card, card.findViewById(R.id.discourse_image), discourse.thumbnailUrl)
        card.findViewById<View>(R.id.discourse_catalogue_card).setOnClickListener {
            (parentFragment as? HomeFragment)?.openDiscourse(discourse)
        }
        bindProgress(card, discourse.id, discourse.totalTracks)
    }

    private fun bindDownloadSummary(card: View, discourseId: String, totalTracks: Int) {
        val completed = downloads.downloadedCount(discourseId)
        card.findViewById<ImageView>(R.id.discourse_download_icon).setColorFilter(
            ContextCompat.getColor(requireContext(), if (completed > 0) R.color.green500 else R.color.discourse_card_secondary)
        )
        card.findViewById<TextView>(R.id.discourse_downloaded_count).apply {
            isVisible = completed > 0
            text = completed.toString()
        }
        val active = downloads.activeDownloadCount(discourseId)
        card.findViewById<ProgressBar>(R.id.discourse_download_progress).apply {
            isVisible = active > 0
            isIndeterminate = false
            progress = (completed * 100 / totalTracks.coerceAtLeast(1)).coerceAtMost(100)
        }
    }

    private fun loadStatsImage(card: View, image: ImageView, url: String?) {
        image.setImageDrawable(UiTools.getDefaultAudioDrawable(requireContext()))
        val imageUrl = resolveDiscourseUrl(url)
        image.tag = imageUrl
        if (imageUrl.isNullOrBlank()) return
        imageJobs[card] = viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = HttpImageLoader.downloadBitmap(imageUrl)
            currentCoroutineContext().ensureActive()
            if (image.tag == imageUrl && bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshHistory()
        model.loadWeekly()
    }
}
