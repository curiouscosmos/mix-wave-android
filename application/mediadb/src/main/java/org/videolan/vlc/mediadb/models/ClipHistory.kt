package org.videolan.vlc.mediadb.models

import androidx.room.Entity

@Entity(tableName = "clip_history", primaryKeys = ["audioId", "startMs", "endMs"])
data class ClipHistory(val audioId: String, val startMs: Long, val endMs: Long, val createdAt: Long)
