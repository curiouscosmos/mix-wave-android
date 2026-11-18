package org.videolan.vlc.media

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.videolan.tools.AppScope
import org.videolan.tools.SingletonHolder
import org.videolan.vlc.database.MediaDatabase
import org.videolan.vlc.mediadb.models.ListenedTrack
import org.videolan.vlc.mediadb.models.ListeningDay

internal class ListeningStatsStore private constructor(context: Context) {
    val database by lazy { MediaDatabase.getInstance(context) }
    private val dao by lazy { database.listeningStatsDao() }
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    var historyGeneration = 0L
        private set

    init {
        AppScope.launch(Dispatchers.IO) {
            for (write in writes) {
                try {
                    write()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("ListeningStats", "Unable to update listening history", error)
                }
            }
        }
    }

    fun record(days: List<ListeningDay>, track: ListenedTrack?) {
        if (days.isNotEmpty() || track != null) writes.trySend { dao.record(days, track) }
    }

    // Called on main, so an active session cannot write pre-clear time after this command.
    fun clear() {
        historyGeneration++
        writes.trySend { dao.clear() }
    }

    companion object : SingletonHolder<ListeningStatsStore, Context>({ ListeningStatsStore(it.applicationContext) })
}

internal class PlaybackListeningStats(context: Context) {
    private val store = ListeningStatsStore.getInstance(context)
    private var generation = store.historyGeneration
    private var session: ListeningSession? = null

    fun start(track: ListenedTrack, now: Long) {
        resetIfCleared()
        if (session?.track != track) {
            finish(now)
            session = ListeningSession(track)
        }
        session?.start(now, Instant.now(), ZoneId.systemDefault())
    }

    fun pause(now: Long, completed: Boolean = false) {
        resetIfCleared()
        session?.flush(now, completed)?.let { (days, track) -> store.record(days, track) }
    }

    fun finish(now: Long, completed: Boolean = false) {
        pause(now, completed)
        discard()
    }

    fun discard() {
        session = null
    }

    private fun resetIfCleared() {
        val next = store.historyGeneration
        if (generation != next) discard()
        generation = next
    }
}
