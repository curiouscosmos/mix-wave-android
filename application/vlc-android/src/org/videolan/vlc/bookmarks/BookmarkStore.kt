package org.videolan.vlc.bookmarks

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.tools.Settings
import kotlin.math.abs

enum class BookmarkAddOutcome { ADDED, LIMIT_REACHED, DUPLICATE, INVALID }

data class BookmarkAddResult(val outcome: BookmarkAddOutcome, val track: BookmarkedTrack?)

class BookmarkStore(
    private val settings: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter<List<BookmarkedTrack>>(
        Types.newParameterizedType(List::class.java, BookmarkedTrack::class.java)
    )
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()

    fun add(track: BookmarkedTrack, positionMs: Long): BookmarkAddResult {
        if (positionMs <= 0L) return BookmarkAddResult(BookmarkAddOutcome.INVALID, null)
        synchronized(lock) {
            val current = mutableState.value
            val existing = current.firstOrNull { it.key == track.key }
            if (existing != null && existing.stamps.size >= MAX_STAMPS)
                return BookmarkAddResult(BookmarkAddOutcome.LIMIT_REACHED, existing)
            if (existing != null && existing.stamps.any { abs(it.positionMs - positionMs) < 1_000L })
                return BookmarkAddResult(BookmarkAddOutcome.DUPLICATE, existing)

            val stamp = BookmarkStamp(positionMs, now())
            val merged = (existing ?: track).copy(
                kind = track.kind,
                title = track.title.ifBlank { existing?.title.orEmpty() },
                subtitle = track.subtitle ?: existing?.subtitle,
                artwork = track.artwork ?: existing?.artwork,
                durationMs = track.durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0L,
                audio = track.audio ?: existing?.audio,
                uri = track.uri ?: existing?.uri,
                updatedAt = stamp.createdAt,
                stamps = (existing?.stamps.orEmpty() + stamp).sortedBy { it.positionMs }
            )
            val updated = (current.filterNot { it.key == track.key } + merged)
                .sortedByDescending { it.updatedAt }
            mutableState.value = updated
            persist(updated)
            return BookmarkAddResult(BookmarkAddOutcome.ADDED, merged)
        }
    }

    fun remove(trackKey: String, positionMs: Long): Boolean {
        synchronized(lock) {
            val current = mutableState.value
            val track = current.firstOrNull { it.key == trackKey } ?: return false
            val stamps = track.stamps.filterNot { it.positionMs == positionMs }
            if (stamps.size == track.stamps.size) return false
            val updated = if (stamps.isEmpty()) {
                current.filterNot { it.key == trackKey }
            } else {
                current.map {
                    if (it.key == trackKey) it.copy(
                        stamps = stamps,
                        updatedAt = stamps.maxOf(BookmarkStamp::createdAt)
                    ) else it
                }
            }.sortedByDescending { it.updatedAt }
            mutableState.value = updated
            persist(updated)
            return true
        }
    }

    fun stampsFor(trackKey: String): List<BookmarkStamp> =
        synchronized(lock) { mutableState.value.firstOrNull { it.key == trackKey }?.stamps.orEmpty() }

    fun countFor(trackKey: String): Int = stampsFor(trackKey).size

    fun count(): Int = synchronized(lock) { mutableState.value.size }

    fun page(offset: Int, limit: Int = PAGE_SIZE): List<BookmarkedTrack> =
        synchronized(lock) { mutableState.value.drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0)) }

    private fun load(): List<BookmarkedTrack> {
        val json = settings.getString(KEY_TRACKS, null) ?: return emptyList()
        return runCatching { adapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    private fun persist(tracks: List<BookmarkedTrack>) {
        settings.edit()
            .putInt(KEY_VERSION, SCHEMA_VERSION)
            .putString(KEY_TRACKS, adapter.toJson(tracks))
            .apply()
    }

    companion object {
        const val MAX_STAMPS = 20
        const val PAGE_SIZE = 24
        private const val KEY_TRACKS = "osho_api_bookmark_tracks"
        private const val KEY_VERSION = "osho_api_bookmark_tracks_version"
        private const val SCHEMA_VERSION = 1
        private val stores = HashMap<SharedPreferences, BookmarkStore>()

        fun shared(settings: SharedPreferences) = synchronized(stores) {
            stores.getOrPut(settings) { BookmarkStore(settings) }
        }

        fun shared(context: Context) = shared(Settings.getInstance(context.applicationContext))
    }
}
