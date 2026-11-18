package org.videolan.vlc.mediadb.models

import androidx.room.Entity

@Entity(tableName = "clip_history", primaryKeys = ["audioId", "startMs", "endMs"])
data class ClipHistory(
    val audioId: String, val startMs: Long, val endMs: Long, val createdAt: Long,
    val trackTitle: String? = null, val discourseId: String? = null, val discourseTitle: String? = null
)

data class ClipTrackCount(
    val audioId: String, val trackTitle: String?, val discourseId: String?, val discourseTitle: String?, val count: Int
)
