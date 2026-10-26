package org.videolan.vlc.discourse

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.tools.ErrorReporter
import org.videolan.vlc.R
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.videolan.tools.Settings

class DiscourseRepository(
    context: Context,
    private val api: DiscourseApi = DiscourseApiClient.instance
) {
    private val appContext = context.applicationContext
    private val settings = Settings.getInstance(context)
    private val likesStore = DiscourseLikesStore.shared(settings)
    private val favouritesStore = DiscourseFavouritesStore.shared(settings)
    internal val likes get() = likesStore.state

    suspend fun refreshLikes() {
        try {
            likesStore.refresh { api.likes(userId) }
            // Do not let an older GET overwrite a like that is still being acknowledged.
            if (likesStore.state.value.pending.isEmpty()) {
                favouritesStore.reconcile(
                    likedDiscourses, likedAudios,
                    recentlyPlayedDiscourses, recentlyPlayedAudios
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ErrorReporter.error("DiscourseRepository", "API request failed", error)
        }
    }
    private val statsStore = DiscourseStatsStore(appContext)
    private val recentlyPlayedAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<Discourse>>(Types.newParameterizedType(List::class.java, Discourse::class.java))
    private val statsDiscoursesAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<Discourse>>(Types.newParameterizedType(List::class.java, Discourse::class.java))
    private val statsAudiosAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<DiscourseAudio>>(Types.newParameterizedType(List::class.java, DiscourseAudio::class.java))
    private val recentlyPlayedAudiosAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<DiscourseAudio>>(Types.newParameterizedType(List::class.java, DiscourseAudio::class.java))

    suspend fun apiIndex() = safely<ApiIndex?>(null) { api.index() }

    suspend fun getDiscourses(
        page: Int = 1,
        search: String? = null,
        isAudioCleaned: Boolean? = null,
        language: String? = null,
        sort: String? = null,
        forceRefresh: Boolean = false
    ) = fetch {
        api.discourses(page, search.cleanQuery(), isAudioCleaned, language.cleanQuery(),
            sort.cleanQuery(), cacheControl(forceRefresh))
    }

    val catalogueLanguage: String?
        get() = settings.getString(KEY_CATALOGUE_LANGUAGE, null)

    val catalogueSort: String?
        get() = settings.getString(KEY_CATALOGUE_SORT, null)

    fun saveCatalogueFilters(language: String?, sort: String?) = settings.edit()
        .putString(KEY_CATALOGUE_LANGUAGE, language)
        .putString(KEY_CATALOGUE_SORT, sort)
        .apply()

    suspend fun getDiscourseAudios(
        page: Int = 1,
        search: String? = null,
        discourseName: String? = null,
        language: String? = null,
        forceRefresh: Boolean = false
    ) = fetch { api.discourseAudios(page, search.cleanQuery(), discourseName.cleanQuery(), language.cleanQuery(), cacheControl(forceRefresh)) }

    suspend fun getDiscourseAudios(discourseId: String, forceRefresh: Boolean = false) =
        fetch { api.discourseAudios(discourseId, cacheControl(forceRefresh)) }

    /**
     * Resolves a discourse from its ID for deep links. The API has no fetch-by-ID route, so the
     * discourse metadata is reconstructed from its track list.
     */
    suspend fun getDiscourseById(discourseId: String): Discourse? = safely(null) {
        val response = api.discourseAudios(discourseId, cacheControl(false))
        val first = response.data.firstOrNull() ?: return@safely null
        val tracksCount = maxOf(response.meta.totalRecords, response.data.size)
        recentlyPlayedDiscourses.firstOrNull { it.id == discourseId }?.copy(totalTracks = tracksCount)
            ?: Discourse(
                id = discourseId,
                title = first.discourseName,
                thumbnailUrl = first.discourseThumbnailUrl,
                isAudioCleaned = false,
                language = first.language,
                slug = null,
                createdAt = first.createdAt,
                updatedAt = first.updatedAt,
                totalTracks = tracksCount
            )
    }

    suspend fun getWeeklyDiscourseStats(forceRefresh: Boolean = false): List<Discourse> =
        getWeeklyStats(KEY_STATS_DISCOURSES, statsDiscoursesAdapter, forceRefresh) {
            api.discourseStats(cacheControl = cacheControl(forceRefresh)).data
        }

    suspend fun getWeeklyAudioStats(forceRefresh: Boolean = false): List<DiscourseAudio> =
        getWeeklyStats(KEY_STATS_AUDIOS, statsAudiosAdapter, forceRefresh) {
            api.discourseAudioStats(cacheControl = cacheControl(forceRefresh)).data
        }

    suspend fun likeDiscourse(id: String, totalLikes: Int = 0): LikeData? =
        safely(null) {
            likesStore.like(id, audio = false, totalLikes = totalLikes) { api.likeDiscourse(id, LikeRequest(userId)).data }
            .also { it?.let { result -> updateCachedLikeCounts(discourseId = id, discourseCount = result.totalLikes) } }
        }

    suspend fun likeDiscourseAudio(id: String, totalLikes: Int = 0): LikeData? =
        safely(null) {
            likesStore.like(id, audio = true, totalLikes = totalLikes) { api.likeDiscourseAudio(id, LikeRequest(userId)).data }
            .also { it?.let { result -> updateCachedLikeCounts(audioId = id, audioCount = result.totalLikes) } }
        }

    suspend fun unlikeDiscourse(id: String, totalLikes: Int = 0): UnlikeData? =
        safely(null) {
            likesStore.unlike(id, audio = false, totalLikes = totalLikes) { api.unlike(userId, discourseId = id).data }
            .also { it?.let { result -> updateCachedLikeCounts(discourseId = id, discourseCount = result.totalLikes) } }
        }

    suspend fun unlikeDiscourseAudio(id: String, totalLikes: Int = 0): UnlikeData? =
        safely(null) {
            likesStore.unlike(id, audio = true, totalLikes = totalLikes) {
                api.unlike(userId, audioId = id).data
            }.also { it?.let { result ->
                updateCachedLikeCounts(audioId = id, audioCount = result.totalLikes)
            } }
        }

    suspend fun toggleDiscourseLike(id: String, totalLikes: Int = 0, discourse: Discourse? = null) {
        if (id in likedDiscourses) {
            if (unlikeDiscourse(id, totalLikes) != null) favouritesStore.removeDiscourse(id)
        } else {
            val result = likeDiscourse(id, totalLikes)
            if (result != null && discourse != null) {
                favouritesStore.addDiscourse(discourse.copy(totalLikes = result.totalLikes))
            }
        }
    }

    suspend fun toggleDiscourseAudioLike(id: String, totalLikes: Int = 0, audio: DiscourseAudio? = null) {
        if (id in likedAudios) {
            if (unlikeDiscourseAudio(id, totalLikes) != null) favouritesStore.removeTrack(id)
        } else {
            val result = likeDiscourseAudio(id, totalLikes)
            if (result != null && audio != null) {
                favouritesStore.addTrack(audio.copy(totalLikes = result.totalLikes))
            }
        }
    }

    private fun updateCachedLikeCounts(
        discourseId: String? = null, discourseCount: Int? = null, audioId: String? = null, audioCount: Int? = null
    ) {
        if (discourseId != null && discourseCount != null) {
            favouritesStore.updateDiscourseCount(discourseId, discourseCount)
            val update: (Discourse) -> Discourse = { if (it.id == discourseId) it.copy(totalLikes = discourseCount) else it }
            updateCachedRecords(KEY_RECENTLY_PLAYED_DISCOURSES, recentlyPlayedAdapter, update)
            updateCachedRecords(KEY_STATS_DISCOURSES, statsDiscoursesAdapter, update)
        }
        if (audioId != null && audioCount != null) {
            favouritesStore.updateAudioCount(audioId, audioCount)
            val update: (DiscourseAudio) -> DiscourseAudio = { if (it.id == audioId) it.copy(totalLikes = audioCount) else it }
            updateCachedRecords(KEY_RECENTLY_PLAYED_AUDIOS, recentlyPlayedAudiosAdapter, update)
            updateCachedRecords(KEY_STATS_AUDIOS, statsAudiosAdapter, update)
        }
    }

    private fun <T> updateCachedRecords(key: String, adapter: com.squareup.moshi.JsonAdapter<List<T>>, update: (T) -> T) {
        synchronized(settings) {
            val json = settings.getString(key, null) ?: return
            val records = runCatching { adapter.fromJson(json) }.getOrNull() ?: return
            val updated = records.map(update)
            if (records != updated) settings.edit().putString(key, adapter.toJson(updated)).apply()
        }
    }

    suspend fun recordListeningStats(discourseId: String, audioId: String) = safely(Unit) {
        if (!statsStore.shouldSend(discourseId, audioId)) return@safely
        api.recordStats(StatsRequest(userId, discourseId, audioId))
        statsStore.markSent(discourseId, audioId)
    }

    val likedDiscourses: Set<String>
        get() = likesStore.state.value.discourseIds

    val likedAudios: Set<String>
        get() = likesStore.state.value.audioIds

    val recentlyPlayedDiscourses: List<Discourse>
        get() = settings.getString(KEY_RECENTLY_PLAYED_DISCOURSES, null)?.let { json ->
            runCatching { recentlyPlayedAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
        } ?: emptyList()

    fun recordRecentlyPlayed(discourse: Discourse) {
        val recent = recentlyPlayedDiscourses.filterNot { it.id == discourse.id }
        val target = DiscourseLikesStore.Target(discourse.id, false)
        val count = likes.value.counts[target]?.takeIf { target !in likes.value.pending }
        val updated = count?.let { discourse.copy(totalLikes = it) } ?: discourse
        settings.edit()
            .putString(KEY_RECENTLY_PLAYED_DISCOURSES, recentlyPlayedAdapter.toJson((listOf(updated) + recent).take(MAX_RECENTLY_PLAYED)))
            .apply()
    }

    val recentlyPlayedAudios: List<DiscourseAudio>
        get() = settings.getString(KEY_RECENTLY_PLAYED_AUDIOS, null)?.let { json ->
            runCatching { recentlyPlayedAudiosAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
        } ?: emptyList()

    fun recordRecentlyPlayed(audio: DiscourseAudio) {
        val recent = recentlyPlayedAudios.filterNot { it.id == audio.id }
        val target = DiscourseLikesStore.Target(audio.id, true)
        val count = likes.value.counts[target]?.takeIf { target !in likes.value.pending }
        val updated = count?.let { audio.copy(totalLikes = it) } ?: audio
        settings.edit()
            .putString(KEY_RECENTLY_PLAYED_AUDIOS, recentlyPlayedAudiosAdapter.toJson((listOf(updated) + recent).take(MAX_RECENTLY_PLAYED)))
            .apply()
    }

    private suspend fun <T> getWeeklyStats(
        key: String,
        adapter: com.squareup.moshi.JsonAdapter<List<T>>,
        forceRefresh: Boolean,
        request: suspend () -> List<T>
    ): List<T> {
        val cached = settings.getString(key, null)?.let { json ->
            runCatching { adapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
        }.orEmpty()
        val cachedAt = settings.getLong("$key.time", 0L)
        if (!forceRefresh && cachedAt > System.currentTimeMillis() - STATS_CACHE_TTL) return cached
        return try {
            request().also { saveStats(key, adapter, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ErrorReporter.error("DiscourseRepository", "API request failed", error)
            cached
        }
    }

    private fun <T> saveStats(key: String, adapter: com.squareup.moshi.JsonAdapter<List<T>>, value: List<T>) {
        settings.edit()
            .putString(key, adapter.toJson(value))
            .putLong("$key.time", System.currentTimeMillis())
            .apply()
    }

    private val userId: String
        get() = OshoUserIdentity.ensure(appContext)

    private suspend fun <T> fetch(request: suspend () -> T): T = try {
        request()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ErrorReporter.error("DiscourseRepository", "API request failed", error)
        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, R.string.discourse_server_error, Toast.LENGTH_LONG).show()
        }
        throw error
    }

    private suspend fun <T> safely(fallback: T, request: suspend () -> T): T = try {
        request()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ErrorReporter.error("DiscourseRepository", "API request failed", error)
        fallback
    }

    private fun String?.cleanQuery() = this?.trim()?.takeIf(String::isNotEmpty)

    private companion object {
        const val KEY_RECENTLY_PLAYED_DISCOURSES = "osho_api_recently_played_discourses"
        const val KEY_RECENTLY_PLAYED_AUDIOS = "osho_api_recently_played_audios"
        const val KEY_CATALOGUE_LANGUAGE = "osho_api_catalogue_language"
        const val KEY_CATALOGUE_SORT = "osho_api_catalogue_sort"
        const val MAX_RECENTLY_PLAYED = 24
        const val KEY_STATS_DISCOURSES = "osho_api_stats_discourses"
        const val KEY_STATS_AUDIOS = "osho_api_stats_audios"
        const val STATS_CACHE_TTL = 3L * 60L * 60L * 1000L
    }
}
