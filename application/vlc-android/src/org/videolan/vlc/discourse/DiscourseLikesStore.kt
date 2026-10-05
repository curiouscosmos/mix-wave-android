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
        val pending: Set<Target> = emptySet(),
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
        // Likes are one-way. Preserve confirmed local writes and pre-existing offline acknowledgements.
        settings.edit()
            .putStringSet(DISCOURSES, response.discourseIds + ids(DISCOURSES))
            .putStringSet(AUDIOS, response.audioIds + ids(AUDIOS))
            .putLong(FETCHED_AT, now())
            .apply()
        synchronized(this) { publish() }
    }

    suspend fun like(id: String, audio: Boolean, totalLikes: Int, request: suspend () -> LikeData): LikeData? {
        val target = Target(id, audio)
        val previousCount: Int?
        synchronized(this) {
            val current = mutableState.value
            if (current.liked(target)) return null
            previousCount = current.counts[target]
            mutableState.value = current.copy(
                discourseIds = current.discourseIds + if (audio) emptySet() else setOf(id),
                audioIds = current.audioIds + if (audio) setOf(id) else emptySet(),
                pending = current.pending + target,
                counts = current.counts + (target to ((previousCount ?: totalLikes) + 1))
            )
        }
        try {
            return mutex.withLock {
                val result = request()
                check(result.liked) { "Like was not acknowledged" }
                val key = if (audio) AUDIOS else DISCOURSES
                settings.edit().putStringSet(key, ids(key) + id).apply()
                synchronized(this) {
                    val current = mutableState.value
                    mutableState.value = current.copy(counts = current.counts + (target to result.totalLikes))
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

    private fun publish() {
        val current = mutableState.value
        mutableState.value = current.copy(
            discourseIds = ids(DISCOURSES) + current.pending.filterNot { it.audio }.map { it.id },
            audioIds = ids(AUDIOS) + current.pending.filter { it.audio }.map { it.id }
        )
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
