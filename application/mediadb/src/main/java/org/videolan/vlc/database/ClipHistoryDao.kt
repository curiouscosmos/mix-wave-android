package org.videolan.vlc.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.videolan.vlc.mediadb.models.ClipHistory

@Dao
interface ClipHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun save(entry: ClipHistory): Long

    @Query("SELECT * FROM clip_history WHERE audioId = :audioId ORDER BY createdAt DESC, startMs ASC, endMs ASC")
    fun getForAudio(audioId: String): List<ClipHistory>
}
