package org.videolan.vlc.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.videolan.vlc.mediadb.models.ListenedTrack
import org.videolan.vlc.mediadb.models.ListeningCounts
import org.videolan.vlc.mediadb.models.ListeningDay

@Dao
abstract class ListeningStatsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insert(day: ListeningDay): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insert(track: ListenedTrack): Long

    @Query("UPDATE listening_day SET durationMs = durationMs + :duration WHERE day = :day")
    protected abstract fun addDuration(day: Long, duration: Long)

    @Query("UPDATE listened_track SET sessions = sessions + 1 WHERE trackId = :id")
    protected abstract fun addSession(id: String)

    @Transaction
    open fun record(days: List<ListeningDay>, track: ListenedTrack?) {
        for (day in days) if (insert(day) == -1L) addDuration(day.day, day.durationMs)
        if (track != null && insert(track) == -1L) addSession(track.trackId)
    }

    @Query("SELECT * FROM listening_day")
    abstract fun days(): Flow<List<ListeningDay>>

    @Query("""
        SELECT COUNT(*) AS tracks, COUNT(DISTINCT discourseId) AS discourses,
        COUNT(CASE WHEN sessions > 1 THEN 1 END) AS repeatedTracks,
        COUNT(DISTINCT CASE WHEN sessions > 1 THEN discourseId END) AS repeatedDiscourses
        FROM listened_track
    """)
    abstract fun counts(): Flow<ListeningCounts>

    @Query("DELETE FROM listening_day")
    protected abstract fun clearDays()

    @Query("DELETE FROM listened_track")
    protected abstract fun clearTracks()

    @Transaction
    open fun clear() {
        clearDays()
        clearTracks()
    }
}
