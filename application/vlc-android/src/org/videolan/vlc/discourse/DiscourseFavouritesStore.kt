package org.videolan.vlc.discourse

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.tools.Settings

/** A liked discourse track, kept with its metadata so the playlist works without a network call. */
data class FavouriteTrack(val audio: DiscourseAudio, val likedAt: Long)

/** A liked discourse, kept with its metadata so the list works without a network call. */
data class FavouriteDiscourse(val discourse: Discourse, val likedAt: Long)

/**
 * Local source of truth for the "Favourite Tracks" and "Favourite Discourses" playlists.
 *
 * The API only returns liked IDs, so the full records are captured when the like succeeds. The
 * store is the only persistent playlist storage; the playlists themselves are virtual.
 */
class DiscourseFavouritesStore(
    private val settings: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis
) {
    data class State(
        val tracks: List<FavouriteTrack> = emptyList(),
        val discourses: List<FavouriteDiscourse> = emptyList()
    )

    private val lock = Any()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val tracksAdapter = moshi.adapter<List<FavouriteTrack>>(
        Types.newParameterizedType(List::class.java, FavouriteTrack::class.java)
    )
    private val discoursesAdapter = moshi.adapter<List<FavouriteDiscourse>>(
        Types.newParameterizedType(List::class.java, FavouriteDiscourse::class.java)
    )
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()

    fun addTrack(audio: DiscourseAudio) = update { current ->
        val entry = FavouriteTrack(audio, now())
        current.copy(tracks = listOf(entry) + current.tracks.filterNot { it.audio.id == audio.id })
    }

    fun removeTrack(id: String) = update { current ->
        current.copy(tracks = current.tracks.filterNot { it.audio.id == id })
    }

    fun addDiscourse(discourse: Discourse) = update { current ->
        val entry = FavouriteDiscourse(discourse, now())
        current.copy(discourses = listOf(entry) + current.discourses.filterNot { it.discourse.id == discourse.id })
    }

    fun removeDiscourse(id: String) = update { current ->
        current.copy(discourses = current.discourses.filterNot { it.discourse.id == id })
    }

    fun updateDiscourseCount(id: String, count: Int) = update { current ->
        current.copy(discourses = current.discourses.map { entry ->
            if (entry.discourse.id == id && entry.discourse.totalLikes != count)
                entry.copy(discourse = entry.discourse.copy(totalLikes = count))
            else entry
        })
    }

    fun updateAudioCount(id: String, count: Int) = update { current ->
        current.copy(tracks = current.tracks.map { entry ->
            if (entry.audio.id == id && entry.audio.totalLikes != count)
                entry.copy(audio = entry.audio.copy(totalLikes = count))
            else entry
        })
    }

    /**
     * Aligns the local playlists with the server's liked-ID sets. Records that are no longer liked
     * are removed. Missing records are hydrated from the cached recently played lists when possible;
     * the API currently has no fetch-by-ID route for anything older.
     */
    fun reconcile(
        likedDiscourseIds: Set<String>,
        likedAudioIds: Set<String>,
        recentDiscourses: List<Discourse> = emptyList(),
        recentAudios: List<DiscourseAudio> = emptyList()
    ) = update { current ->
        val discourses = current.discourses.filter { it.discourse.id in likedDiscourseIds }
        val knownDiscourses = discourses.mapTo(HashSet()) { it.discourse.id }
        val addedDiscourses = likedDiscourseIds.asSequence()
            .filterNot(knownDiscourses::contains)
            .mapNotNull { id -> recentDiscourses.firstOrNull { it.id == id } }
            .map { FavouriteDiscourse(it, now()) }
            .toList()

        val tracks = current.tracks.filter { it.audio.id in likedAudioIds }
        val knownTracks = tracks.mapTo(HashSet()) { it.audio.id }
        val addedTracks = likedAudioIds.asSequence()
            .filterNot(knownTracks::contains)
            .mapNotNull { id -> recentAudios.firstOrNull { it.id == id } }
            .map { FavouriteTrack(it, now()) }
            .toList()

        current.copy(discourses = discourses + addedDiscourses, tracks = tracks + addedTracks)
    }

    private fun update(transform: (State) -> State) {
        synchronized(lock) {
            val current = mutableState.value
            val updated = transform(current)
            if (updated == current) return
            mutableState.value = updated
            persist(updated)
        }
    }

    private fun load(): State = State(
        tracks = read(tracksAdapter, KEY_TRACKS),
        discourses = read(discoursesAdapter, KEY_DISCOURSES)
    )

    private fun <T> read(adapter: com.squareup.moshi.JsonAdapter<List<T>>, key: String): List<T> {
        val json = settings.getString(key, null) ?: return emptyList()
        return runCatching { adapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
    }

    private fun persist(state: State) {
        settings.edit()
            .putInt(KEY_VERSION, SCHEMA_VERSION)
            .putString(KEY_TRACKS, tracksAdapter.toJson(state.tracks))
            .putString(KEY_DISCOURSES, discoursesAdapter.toJson(state.discourses))
            .apply()
    }

    companion object {
        private const val KEY_TRACKS = "osho_api_favourite_tracks"
        private const val KEY_DISCOURSES = "osho_api_favourite_discourses"
        private const val KEY_VERSION = "osho_api_favourites_version"
        private const val SCHEMA_VERSION = 1
        private val stores = HashMap<SharedPreferences, DiscourseFavouritesStore>()

        fun shared(settings: SharedPreferences) = synchronized(stores) {
            stores.getOrPut(settings) { DiscourseFavouritesStore(settings) }
        }

        fun shared(context: Context) = shared(Settings.getInstance(context.applicationContext))
    }
}
