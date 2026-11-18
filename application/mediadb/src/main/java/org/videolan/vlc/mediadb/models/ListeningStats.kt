package org.videolan.vlc.mediadb.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "listening_day")
data class ListeningDay(@PrimaryKey val day: Long, val durationMs: Long)

@Entity(tableName = "listened_track")
data class ListenedTrack(@PrimaryKey val trackId: String, val discourseId: String?, val sessions: Long = 1)

data class ListeningCounts(val tracks: Int, val discourses: Int, val repeatedTracks: Int, val repeatedDiscourses: Int)
