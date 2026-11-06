package org.videolan.vlc.discourse

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.vlc.database.MediaDatabase
import org.videolan.vlc.mediadb.models.ClipHistory

class ClipHistoryStore(context: Context) {
    private val dao = MediaDatabase.getInstance(context).clipHistoryDao()

    suspend fun save(entry: ClipHistory) = withContext(Dispatchers.IO) {
        require(entry.audioId.isNotBlank() && entry.startMs >= 0 && entry.endMs - entry.startMs >= 1_000)
        dao.save(entry)
    }

    suspend fun getForAudio(audioId: String) = withContext(Dispatchers.IO) { dao.getForAudio(audioId) }
}
