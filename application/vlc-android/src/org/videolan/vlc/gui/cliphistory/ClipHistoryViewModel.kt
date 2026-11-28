package org.videolan.vlc.gui.cliphistory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.videolan.vlc.discourse.ClipHistoryGroup
import org.videolan.vlc.discourse.ClipHistoryStore
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseFavouritesStore
import org.videolan.vlc.discourse.DiscourseRepository
import org.videolan.vlc.discourse.resolveDiscourseUrl
import org.videolan.vlc.mediadb.models.ClipHistory

data class ClipHistoryTrack(
    val audioId: String,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val audio: DiscourseAudio?,
    val clips: List<ClipHistory>
)

class ClipHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ClipHistoryStore(application)
    private val favourites = DiscourseFavouritesStore.shared(application)
    private val repository = DiscourseRepository(application)
    private val loadedTracks = MutableStateFlow<List<ClipHistoryTrack>>(emptyList())
    val tracks = loadedTracks.asStateFlow()
    private val loading = MutableStateFlow(false)
    val isLoading = loading.asStateFlow()
    private val hasMore = MutableStateFlow(false)
    val hasMoreTracks = hasMore.asStateFlow()
    private var loadedCount = 0

    init {
        loadNextPage()
    }

    fun loadNextPage() {
        if (loading.value) return
        viewModelScope.launch {
            loading.value = true
            try {
                val total = store.trackCount()
                if (loadedCount >= total) {
                    hasMore.value = false
                    return@launch
                }
                val page = loadPage(loadedCount)
                if (page.isEmpty()) {
                    hasMore.value = false
                    return@launch
                }
                loadedCount += page.size
                loadedTracks.value = loadedTracks.value + page
                hasMore.value = loadedCount < total
            } finally {
                loading.value = false
            }
        }
    }

    private suspend fun loadPage(offset: Int): List<ClipHistoryTrack> {
        val groups = store.page(PAGE_SIZE, offset)
        if (groups.isEmpty()) return emptyList()
        val cached = audioCache()
        return groups.map { group -> group.toTrack(cached) }
    }

    private fun audioCache(): Map<String, DiscourseAudio> =
        (favourites.state.value.tracks.map { it.audio } + repository.recentlyPlayedAudios)
            .associateBy { it.id }

    private fun ClipHistoryGroup.toTrack(cached: Map<String, DiscourseAudio>): ClipHistoryTrack {
        val audio = cached[summary.audioId]
        return ClipHistoryTrack(
            audioId = summary.audioId,
            title = audio?.title?.takeIf { it.isNotBlank() }
                ?: summary.trackTitle?.takeIf { it.isNotBlank() }
                ?: summary.audioId,
            subtitle = audio?.discourseName?.takeIf { it.isNotBlank() } ?: summary.discourseTitle,
            artwork = resolveDiscourseUrl(audio?.discourseThumbnailUrl),
            audio = audio,
            clips = clips
        )
    }

    companion object {
        const val PAGE_SIZE = 24
    }
}
