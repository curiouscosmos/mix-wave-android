package org.videolan.vlc.discourse

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DiscourseLikesStore(
    private val settings: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis
) {
    data class Target(val id: String, val audio: Boolean)
    data class State(
        val discourseIds: Set<String>,
        val audioIds: Set<String>,
        val pending: Map<Target, Boolean> = emptyMap(),
        val counts: Map<Target, Int> = emptyMap()
    ) {
        fun liked(target: Target) = target.id in if (target.audio) audioIds else discourseIds
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(State(ids(DISCOURSES), ids(AUDIOS)))
    val state = mutableState.asStateFlow()

    suspend fun refresh(request: suspend () -> UserLikesResponse) = mutex.withLock {
        val age = now() - settings.getLong(FETCHED_AT, 0L)
        if (settings.contains(DISCOURSES) && settings.contains(AUDIOS) && settings.contains(FETCHED_AT) &&
            age >= 0 && age < TTL) return@withLock
        val response = request()
        settings.edit()
            .putStringSet(DISCOURSES, response.discourseIds)
            .putStringSet(AUDIOS, response.audioIds)
            .putLong(FETCHED_AT, now())
            .apply()
        synchronized(this) { publish() }
    }

    suspend fun like(id: String, audio: Boolean, totalLikes: Int, request: suspend () -> LikeData): LikeData? =
        change(id, audio, true, totalLikes, count = { it.totalLikes }) {
            request().also { check(it.liked) { "Like was not acknowledged" } }
        }

    suspend fun unlike(id: String, audio: Boolean, totalLikes: Int, discourseId: String? = null,
                       request: suspend () -> UnlikeData): UnlikeData? =
        change(id, audio, false, totalLikes, count = { it.totalLikes },
            parentCount = { result -> discourseId?.let { parent -> result.discourseTotalLikes?.let { Target(parent, false) to it } } },
            request = request)

    private suspend fun <T> change(
        id: String, audio: Boolean, liked: Boolean, totalLikes: Int,
        count: (T) -> Int,
        parentCount: (T) -> Pair<Target, Int>? = { null },
        request: suspend () -> T
    ): T? {
        val target = Target(id, audio)
        val previousCount: Int?
        synchronized(this) {
            val current = mutableState.value
            if (target in current.pending || current.liked(target) == liked) return null
            previousCount = current.counts[target]
            mutableState.value = current.copy(
                discourseIds = if (audio) current.discourseIds else membership(current.discourseIds, id, liked),
                audioIds = if (audio) membership(current.audioIds, id, liked) else current.audioIds,
                pending = current.pending + (target to liked),
                counts = current.counts + (target to ((previousCount ?: totalLikes) + if (liked) 1 else -1).coerceAtLeast(0))
            )
        }
        try {
            return mutex.withLock {
                val result = request()
                val key = if (audio) AUDIOS else DISCOURSES
                settings.edit().putStringSet(key, membership(ids(key), id, liked)).apply()
                synchronized(this) {
                    val current = mutableState.value
                    val counts = current.counts + (target to count(result).coerceAtLeast(0))
                    mutableState.value = current.copy(counts = parentCount(result)?.let { counts + it } ?: counts)
                }
                result
            }
        } catch (error: Exception) {
            synchronized(this) {
                val counts = mutableState.value.counts - target
                mutableState.value = mutableState.value.copy(
                    counts = if (previousCount == null) counts else counts + (target to previousCount)
                )
            }
            throw error
        } finally {
            synchronized(this) {
                mutableState.value = mutableState.value.copy(pending = mutableState.value.pending - target)
                publish()
            }
        }
    }

    private fun ids(key: String) = settings.getStringSet(key, emptySet()).orEmpty().toSet()

    private fun membership(ids: Set<String>, id: String, liked: Boolean) = if (liked) ids + id else ids - id

    private fun publish() {
        val current = mutableState.value
        var discourses = ids(DISCOURSES)
        var audios = ids(AUDIOS)
        current.pending.forEach { (target, liked) ->
            if (target.audio) audios = membership(audios, target.id, liked)
            else discourses = membership(discourses, target.id, liked)
        }
        mutableState.value = current.copy(discourseIds = discourses, audioIds = audios)
    }

    companion object {
        const val DISCOURSES = "osho_api_liked_discourses"
        const val AUDIOS = "osho_api_liked_audios"
        const val FETCHED_AT = "osho_api_likes_fetched_at"
        const val TTL = 7L * 24 * 60 * 60 * 1000
        private val stores = HashMap<SharedPreferences, DiscourseLikesStore>()
        fun shared(settings: SharedPreferences) = synchronized(stores) {
            stores.getOrPut(settings) { DiscourseLikesStore(settings) }
        }
    }
}
