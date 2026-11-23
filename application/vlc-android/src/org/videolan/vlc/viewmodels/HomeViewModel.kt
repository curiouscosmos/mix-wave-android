package org.videolan.vlc.viewmodels

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.PLAYBACK_HISTORY
import org.videolan.tools.Settings
import org.videolan.vlc.discourse.*
import org.videolan.vlc.media.ListeningStatsStore
import org.videolan.vlc.media.MediaSessionBrowser
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.media.listeningTime
import org.videolan.vlc.mediadb.models.ListeningDay

/** Display snapshots keep mutable medialibrary objects out of diff comparisons. */
data class HomeTrack(val key: String, val title: String?, val subtitle: String?, val artwork: String?, val audio: DiscourseAudio? = null, val playbackTime: Long = 0L) {
    lateinit var media: MediaWrapper
}

class HomeViewModel @JvmOverloads constructor(
    application: Application,
    private val historyLoader: (suspend () -> Pair<List<Discourse>, List<HomeTrack>>)? = null,
    private val discourseLoader: (suspend (Boolean) -> List<Discourse>)? = null,
    private val audioLoader: (suspend (Boolean) -> List<DiscourseAudio>)? = null
) : AndroidViewModel(application) {
    private val repository = DiscourseRepository(application)
    private val playback = DiscoursePlaybackStore(application)
    private val settings = Settings.getInstance(application)
    val recentDiscourses = MutableLiveData<List<Discourse>>(emptyList())
    val recentTracks = MutableLiveData<List<HomeTrack>>(emptyList())
    val weeklyDiscourses = MutableLiveData<List<Discourse>>(emptyList())
    val weeklyTracks = MutableLiveData<List<DiscourseAudio>>(emptyList())
    val showListeningStats = MutableLiveData(false)
    val weekListeningMs = MutableLiveData(0L)
    val refreshing = MutableLiveData(false)
    private var historyJob: Job? = null
    private var statsJob: Job? = null
    private var listeningStatsJob: Job? = null
    private val preferences = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PLAYBACK_HISTORY || key == "osho_api_recently_played_audios" || key == "osho_api_recently_played_discourses")
            viewModelScope.launch { refreshHistory() }
    }
    private val history = Medialibrary.HistoryCb { viewModelScope.launch { refreshHistory() } }

    init {
        settings.registerOnSharedPreferenceChangeListener(preferences)
        Medialibrary.getInstance().addHistoryCb(history)
    }

    fun refreshHistory() {
        historyJob?.cancel()
        if (!settings.getBoolean(PLAYBACK_HISTORY, true)) {
            recentDiscourses.value = emptyList()
            recentTracks.value = emptyList()
            return
        }
        historyJob = viewModelScope.launch {
            val result = historyLoader?.invoke() ?: withContext(Dispatchers.IO) {
                val audios = repository.recentlyPlayedAudios
                playback.register(audios)
                val local = Medialibrary.getInstance().history(Medialibrary.HISTORY_TYPE_LOCAL)
                    ?.filter { MediaSessionBrowser.isMediaAudio(it) }.orEmpty()
                val tracks = (audios.map { it.toMediaWrapper(getApplication()) } + local)
                    .distinctBy { it.tag ?: it.uri }
                    .map { media ->
                        HomeTrack(media.discourseAudioSnapshot()?.id ?: media.uri.toString(), media.title,
                            MediaUtils.getDisplaySubtitle(getApplication(), media) ?: media.albumName ?: media.artistName.orEmpty(),
                            media.artworkMrl, media.discourseAudioSnapshot(), media.time).apply { this.media = media }
                    }
                repository.recentlyPlayedDiscourses to tracks
            }
            ensureActive()
            if (recentDiscourses.value != result.first) recentDiscourses.value = result.first
            if (recentTracks.value != result.second) recentTracks.value = result.second
        }
    }

    fun loadWeekly(force: Boolean = false) {
        if (statsJob?.isActive == true) return
        statsJob = viewModelScope.launch {
            refreshing.value = true
            try {
                supervisorScope {
                    launch {
                        try {
                            val result = discourseLoader?.invoke(force) ?: withContext(Dispatchers.IO) { repository.getWeeklyDiscourseStats(force) }
                            if (weeklyDiscourses.value != result) weeklyDiscourses.value = result
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* Keep the visible section when a refresh fails. */ }
                    }
                    launch {
                        try {
                            val result = audioLoader?.invoke(force) ?: withContext(Dispatchers.IO) {
                                repository.getWeeklyAudioStats(force).also { playback.register(it) }
                            }
                            if (weeklyTracks.value != result) weeklyTracks.value = result
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* Keep the visible section when a refresh fails. */ }
                    }
                }
            } finally { refreshing.value = false }
        }
    }

    fun refreshListeningStats() {
        listeningStatsJob?.cancel()
        if (!settings.getBoolean(PLAYBACK_HISTORY, true)) {
            showListeningStats.value = false
            weekListeningMs.value = 0L
            return
        }
        listeningStatsJob = viewModelScope.launch {
            val (visible, weekMs) = withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                val days = ListeningStatsStore.getInstance(getApplication())
                    .database.listeningStatsDao().days().first()
                hasListeningActivityBeforeToday(days, today) to listeningTime(days, today).week
            }
            showListeningStats.value = visible
            weekListeningMs.value = weekMs
        }
    }

    override fun onCleared() {
        settings.unregisterOnSharedPreferenceChangeListener(preferences)
        Medialibrary.getInstance().removeHistoryCb(history)
        listeningStatsJob?.cancel()
        super.onCleared()
    }
}

internal fun hasListeningActivityBeforeToday(days: List<ListeningDay>, today: LocalDate): Boolean {
    val start = today.minusDays(6).toEpochDay()
    val yesterday = today.minusDays(1).toEpochDay()
    return days.any { it.day in start..yesterday && it.durationMs > 0 }
}
