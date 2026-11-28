package org.videolan.vlc.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.videolan.vlc.mediadb.models.ClipHistory
import org.videolan.vlc.mediadb.models.ClipTrackCount
import org.videolan.vlc.mediadb.models.ClipTrackSummary

@Dao
interface ClipHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun save(entry: ClipHistory): Long

    @Query("SELECT * FROM clip_history WHERE audioId = :audioId ORDER BY createdAt DESC, startMs ASC, endMs ASC")
    fun getForAudio(audioId: String): List<ClipHistory>

    @Query("""
        SELECT audioId, MAX(trackTitle) AS trackTitle, MAX(discourseId) AS discourseId,
        MAX(discourseTitle) AS discourseTitle, COUNT(*) AS count
        FROM clip_history GROUP BY audioId
    """)
    fun countsByTrack(): Flow<List<ClipTrackCount>>

    @Query("""
        UPDATE clip_history SET trackTitle = :title, discourseId = :discourse, discourseTitle = :discourseTitle
        WHERE audioId = :audio AND discourseId IS NULL
    """)
    fun fillMetadata(audio: String, title: String, discourse: String, discourseTitle: String)

    @Query("""
        SELECT audioId, MAX(trackTitle) AS trackTitle, MAX(discourseId) AS discourseId,
        MAX(discourseTitle) AS discourseTitle, MAX(createdAt) AS latestAt, COUNT(*) AS count
        FROM clip_history GROUP BY audioId
        ORDER BY latestAt DESC, audioId ASC
        LIMIT :limit OFFSET :offset
    """)
    fun tracksPage(limit: Int, offset: Int): List<ClipTrackSummary>

    @Query("SELECT COUNT(DISTINCT audioId) FROM clip_history")
    fun trackCount(): Int

    @Query("SELECT * FROM clip_history WHERE audioId IN (:audioIds) ORDER BY createdAt DESC, startMs ASC")
    fun clipsFor(audioIds: List<String>): List<ClipHistory>
}
