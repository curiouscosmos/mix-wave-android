package org.videolan.vlc.discourse

import android.content.Context
import org.videolan.tools.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.content.SharedPreferences

private const val KEY_PREFIX = "osho_api_discourse_playback_position."
private const val KEY_PLAYED_AUDIOS = "osho_api_discourse_played_audios"

internal class DiscoursePlaybackStore(private val settings: SharedPreferences) {
    constructor(context: Context) : this(Settings.getInstance(context.applicationContext))
    private val index = synchronized(indices) { indices.getOrPut(settings) { Index(settings) } }
    val changes get() = index.events.asSharedFlow()

    suspend fun initialize() {
        if (synchronized(index) { index.loaded }) return
        withContext(Dispatchers.IO) { synchronized(index) { index.load() } }
    }

    fun audioProgress(id: String): Int = synchronized(index) { (index.fractions[id].orZero() * 100).toInt() }
    fun discourseProgress(id: String, count: Int): Int = synchronized(index) {
        if (count <= 0) 0 else ((index.sums[id].orZero() / count).coerceIn(0.0, 1.0) * 100).toInt()
    }

    fun register(audios: List<DiscourseAudio>, discourseId: String? = null) = synchronized(index) {
        index.load()
        if (discourseId != null) {
            val ids = audios.mapTo(HashSet()) { it.id }
            index.members.filterValues { it == discourseId }.keys.toList().filter { it !in ids }.forEach {
                index.update(it) { index.members.remove(it); settings.edit().remove("osho_progress_member.$it").apply() }
            }
        }
        audios.forEach { register(it.id, it.discourseId, it.durationSeconds?.takeIf { seconds -> seconds.isFinite() && seconds > 0 }?.times(1000)?.toLong() ?: 0) }
    }

    fun register(id: String, discourseId: String, duration: Long) = synchronized(index) {
        index.load()
        if (index.members[id] == discourseId && (duration <= 0 || index.durations[id] == duration)) return@synchronized
        index.update(id) {
            index.members[id] = discourseId
            if (duration > 0) index.durations[id] = duration
            settings.edit().putString("osho_progress_member.$id", discourseId)
                .putLong("osho_progress_duration.$id", index.durations[id] ?: 0).apply()
        }
    }

    fun complete(id: String) = synchronized(index) {
        index.load()
        index.update(id) { index.completed.add(id); settings.edit().putStringSet("osho_progress_completed", index.completed.toSet()).apply() }
    }

    fun position(audioId: String): Long? = settings.getLong(key(audioId), -1L).takeIf { it >= 0L }

    fun save(audioId: String, position: Long) {
        synchronized(index) {
            index.load()
            index.update(audioId) {
                index.positions[audioId] = position.coerceAtLeast(0L)
                settings.edit().putLong(key(audioId), position.coerceAtLeast(0L)).apply()
            }
        }
    }

    fun clear(audioId: String) {
        synchronized(index) {
            index.load()
            index.update(audioId) { index.positions.remove(audioId); settings.edit().remove(key(audioId)).apply() }
        }
    }

    fun isPlayed(audioId: String): Boolean = playedAudios.contains(audioId)

    fun markPlayed(audioId: String) {
        settings.edit().putStringSet(KEY_PLAYED_AUDIOS, playedAudios + audioId).apply()
    }

    fun clearPlayed(audioId: String) {
        settings.edit().putStringSet(KEY_PLAYED_AUDIOS, playedAudios - audioId).apply()
    }

    private val playedAudios: Set<String>
        get() = settings.getStringSet(KEY_PLAYED_AUDIOS, emptySet()).orEmpty()

    private fun key(audioId: String) = "$KEY_PREFIX$audioId"

    data class Change(val audioId: String, val discourseIds: Set<String>)

    private class Index(val settings: SharedPreferences) {
        var loaded = false
        val members = HashMap<String, String>()
        val durations = HashMap<String, Long>()
        val positions = HashMap<String, Long>()
        val completed = HashSet<String>()
        val fractions = HashMap<String, Double>()
        val sums = HashMap<String, Double>()
        val events = MutableSharedFlow<Change>(extraBufferCapacity = 64)
        fun fraction(id: String): Double = when {
            id in completed -> 1.0
            (durations[id] ?: 0) <= 0 -> 0.0
            else -> ((positions[id] ?: 0).toDouble() / durations.getValue(id)).coerceIn(0.0, 1.0)
        }
        fun load() {
            if (loaded) return
            settings.all.forEach { (key, value) -> when {
                key.startsWith(KEY_PREFIX) && value is Long -> positions[key.removePrefix(KEY_PREFIX)] = value
                key.startsWith("osho_progress_member.") && value is String -> members[key.removePrefix("osho_progress_member.")] = value
                key.startsWith("osho_progress_duration.") && value is Long -> durations[key.removePrefix("osho_progress_duration.")] = value
            } }
            completed.addAll(settings.getStringSet("osho_progress_completed", emptySet()).orEmpty())
            (positions.keys + completed).forEach { fractions[it] = fraction(it) }
            members.forEach { (id, discourse) ->
                fractions[id] = fraction(id)
                sums[discourse] = sums[discourse].orZero() + fraction(id)
            }
            loaded = true
        }
        fun update(id: String, action: () -> Unit) {
            val oldMember = members[id]
            val old = fractions[id].orZero()
            action()
            val newMember = members[id]
            val new = fraction(id)
            fractions[id] = new
            oldMember?.let { sums[it] = sums[it].orZero() - old }
            newMember?.let { sums[it] = sums[it].orZero() + new }
            events.tryEmit(Change(id, setOfNotNull(oldMember, newMember)))
        }
    }
    private companion object {
        val indices = HashMap<SharedPreferences, Index>()
        fun Double?.orZero() = this ?: 0.0
    }
}
