package org.videolan.vlc.gui.audio

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.ProgressBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.videolan.vlc.discourse.DiscoursePlaybackStore
import org.videolan.vlc.discourse.discoursePlaybackIds
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.tools.HttpImageLoader
import org.videolan.tools.PLAYBACK_HISTORY
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseRepository
import org.videolan.vlc.discourse.playDiscourseAudio
import org.videolan.vlc.discourse.resolveDiscourseUrl
import org.videolan.vlc.discourse.toMediaWrapper
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.getAudioIconDrawable
import org.videolan.vlc.gui.helpers.loadImage
import org.videolan.vlc.media.MediaSessionBrowser
import org.videolan.vlc.media.MediaUtils

class RecentlyPlayedDiscoursesFragment : Fragment(R.layout.recently_played_discourses) {
    private var statsJob: Job? = null
    private val progressCards = HashMap<View, Pair<String, Int?>>()
    private val audioCards = HashMap<String, MutableSet<View>>()
    private val discourseCards = HashMap<String, MutableSet<View>>()
    private val playbackStore by lazy { DiscoursePlaybackStore(requireContext()) }
    private fun bindProgress(card: View, id: String, count: Int?) {
        progressCards[card] = id to count
        (if (count == null) audioCards else discourseCards).getOrPut(id) { HashSet() }.add(card)
        updateProgress(card, id, count)
    }
    private fun updateProgress(card: View, id: String, count: Int?) {
        val percent = if (count == null) playbackStore.audioProgress(id) else playbackStore.discourseProgress(id, count)
        card.findViewById<ProgressBar>(R.id.listening_progress).progress = percent
        val titleId = if (count == null) R.id.recently_played_track_title else R.id.recently_played_discourse_title
        card.contentDescription = "${card.findViewById<TextView>(titleId).text}. ${getString(R.string.discourse_listening_percentage, percent)}"
    }
    private fun clearCards(container: ViewGroup) {
        for (i in 0 until container.childCount) {
            val card = container.getChildAt(i)
            progressCards.remove(card)?.let { (id, count) ->
                val index = if (count == null) audioCards else discourseCards
                index[id]?.let { cards -> if (cards.remove(card) && cards.isEmpty()) index.remove(id) }
            }
        }
        container.removeAllViews()
    }
    override fun onDestroyView() {
        progressCards.clear()
        audioCards.clear()
        discourseCards.clear()
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<org.videolan.vlc.gui.view.SwipeRefreshLayout>(R.id.all_stats_swipe)
            .setOnRefreshListener { loadStats(true) }
        loadStats()
        viewLifecycleOwner.lifecycleScope.launch {
            playbackStore.initialize()
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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

    private fun render() {
        val view = view ?: return
        val list = DiscourseRepository(requireContext()).recentlyPlayedDiscourses
        val section = view.findViewById<View>(R.id.recently_played_discourses_section)
        val container = view.findViewById<ViewGroup>(R.id.recently_played_discourses_list)
        clearCards(container)
        if (list.isEmpty()) {
            section.isVisible = false
        } else {
            section.isVisible = true
            list.forEach { discourse ->
                val card = layoutInflater.inflate(R.layout.recently_played_discourse_card, container, false)
                card.findViewById<TextView>(R.id.recently_played_discourse_title).text = discourse.title
                card.findViewById<TextView>(R.id.recently_played_discourse_meta).text = getString(
                    R.string.discourse_counts,
                    discourse.totalTracks,
                    discourse.totalLikes
                )
                loadImage(card.findViewById(R.id.recently_played_discourse_image), discourse)
                card.setOnClickListener { (parentFragment as? HomeFragment)?.openDiscourse(discourse) }
                bindProgress(card, discourse.id, discourse.totalTracks)
                container.addView(card)
            }
        }
    }

    private fun loadTracks() {
        val view = view ?: return
        val section = view.findViewById<View>(R.id.recently_played_tracks_section)
        val container = view.findViewById<ViewGroup>(R.id.recently_played_tracks_list)
        if (!Settings.getInstance(requireContext()).getBoolean(PLAYBACK_HISTORY, true)) {
            section.isVisible = false
            return
        }
        lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                val repository = DiscourseRepository(requireContext())
                val localTracks = Medialibrary.getInstance().history(Medialibrary.HISTORY_TYPE_LOCAL)
                    ?.toList()
                    ?.filter { MediaSessionBrowser.isMediaAudio(it) }
                    .orEmpty()
                val audios = repository.recentlyPlayedAudios
                playbackStore.register(audios)
                (audios.map { it.toMediaWrapper(requireContext()) } + localTracks)
                    .distinctBy { it.tag ?: it.uri }
                    .take(MAX_TRACKS)
            }
            if (!isAdded || view !== this@RecentlyPlayedDiscoursesFragment.view) return@launch
            clearCards(container)
            section.isVisible = tracks.isNotEmpty()
            tracks.forEach { addTrackCard(container, it) }
        }
    }

    private fun loadStats(forceRefresh: Boolean = false) {
        statsJob?.cancel()
        statsJob = viewLifecycleOwner.lifecycleScope.launch {
            val repository = DiscourseRepository(requireContext())
            val discourses = withContext(Dispatchers.IO) { repository.getWeeklyDiscourseStats(forceRefresh) }
            val audios = withContext(Dispatchers.IO) {
                repository.getWeeklyAudioStats(forceRefresh).also { playbackStore.register(it) }
            }
            if (!isAdded || view !== this@RecentlyPlayedDiscoursesFragment.view) return@launch
            val root = view ?: return@launch
            renderStats(discourses, audios)
            root.findViewById<org.videolan.vlc.gui.view.SwipeRefreshLayout>(R.id.all_stats_swipe).isRefreshing = false
        }
    }

    private fun renderStats(discourses: List<Discourse>, audios: List<DiscourseAudio>) {
        val root = view ?: return
        val discourseSection = root.findViewById<View>(R.id.weekly_discourses_section)
        val discourseContainer = root.findViewById<ViewGroup>(R.id.weekly_discourses_list)
        clearCards(discourseContainer)
        discourseSection.isVisible = discourses.isNotEmpty()
        discourses.forEach { discourse ->
            val card = layoutInflater.inflate(R.layout.recently_played_discourse_card, discourseContainer, false)
            card.findViewById<TextView>(R.id.recently_played_discourse_title).text = discourse.title
            card.findViewById<TextView>(R.id.recently_played_discourse_meta).text = getString(R.string.weekly_plays, discourse.plays)
            loadImage(card.findViewById(R.id.recently_played_discourse_image), discourse)
            card.setOnClickListener { (parentFragment as? HomeFragment)?.openDiscourse(discourse) }
            bindProgress(card, discourse.id, discourse.totalTracks)
            discourseContainer.addView(card)
        }

        val audioSection = root.findViewById<View>(R.id.weekly_tracks_section)
        val audioContainer = root.findViewById<ViewGroup>(R.id.weekly_tracks_list)
        clearCards(audioContainer)
        audioSection.isVisible = audios.isNotEmpty()
        audios.forEach { audio ->
            val card = layoutInflater.inflate(R.layout.recently_played_track_card, audioContainer, false)
            loadStatsImage(card.findViewById(R.id.recently_played_track_image), audio.discourseThumbnailUrl)
            card.findViewById<TextView>(R.id.recently_played_track_title).text = audio.title
            card.findViewById<TextView>(R.id.recently_played_track_meta).text =
                getString(R.string.weekly_plays, audio.plays)
            card.setOnClickListener { requireContext().playDiscourseAudio(audio) }
            bindProgress(card, audio.id, null)
            audioContainer.addView(card)
        }
    }

    private fun addTrackCard(container: ViewGroup, track: MediaWrapper) {
        val card = layoutInflater.inflate(R.layout.recently_played_track_card, container, false)
        val image = card.findViewById<ImageView>(R.id.recently_played_track_image)
        image.setImageDrawable(getAudioIconDrawable(requireContext(), MediaLibraryItem.TYPE_MEDIA, true))
        loadImage(image, track, card = true)
        card.findViewById<TextView>(R.id.recently_played_track_title).text = track.title
        card.findViewById<TextView>(R.id.recently_played_track_meta).text =
            MediaUtils.getDisplaySubtitle(requireContext(), track) ?: track.albumName ?: track.artistName.orEmpty()
        card.setOnClickListener { MediaUtils.openMedia(requireContext(), track) }
        track.discoursePlaybackIds()?.let { bindProgress(card, it.audioId, null) }
        if (track.discoursePlaybackIds() == null) card.findViewById<ProgressBar>(R.id.listening_progress).isVisible = false
        container.addView(card)
    }

    private fun loadImage(image: ImageView, discourse: Discourse) {
        image.setImageDrawable(UiTools.getDefaultAudioDrawable(requireContext()))
        val imageUrl = resolveDiscourseUrl(discourse.thumbnailUrl)
        image.tag = imageUrl
        if (imageUrl.isNullOrBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = HttpImageLoader.downloadBitmap(imageUrl)
            if (image.tag == imageUrl && bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    private fun loadStatsImage(image: ImageView, url: String?) {
        image.setImageDrawable(UiTools.getDefaultAudioDrawable(requireContext()))
        val imageUrl = resolveDiscourseUrl(url)
        image.tag = imageUrl
        if (imageUrl.isNullOrBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = HttpImageLoader.downloadBitmap(imageUrl)
            if (image.tag == imageUrl && bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        loadTracks()
        loadStats()
    }

    private companion object {
        const val MAX_TRACKS = 24
    }
}
