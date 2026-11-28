package org.videolan.vlc.discourse

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.vlc.database.MediaDatabase
import org.videolan.vlc.mediadb.models.ClipHistory
import org.videolan.vlc.mediadb.models.ClipTrackSummary

data class ClipHistoryGroup(val summary: ClipTrackSummary, val clips: List<ClipHistory>)

class ClipHistoryStore(context: Context) {
    private val dao = MediaDatabase.getInstance(context).clipHistoryDao()

    suspend fun save(entry: ClipHistory) = withContext(Dispatchers.IO) {
        require(entry.audioId.isNotBlank() && entry.startMs >= 0 && entry.endMs - entry.startMs >= 1_000)
        if (dao.save(entry) != -1L) return@withContext
        val title = entry.trackTitle ?: return@withContext
        val discourse = entry.discourseId ?: return@withContext
        val discourseTitle = entry.discourseTitle ?: return@withContext
        dao.fillMetadata(entry.audioId, title, discourse, discourseTitle)
    }

    suspend fun getForAudio(audioId: String) = withContext(Dispatchers.IO) { dao.getForAudio(audioId) }

    suspend fun trackCount() = withContext(Dispatchers.IO) { dao.trackCount() }

    suspend fun page(limit: Int, offset: Int): List<ClipHistoryGroup> = withContext(Dispatchers.IO) {
        val summaries = dao.tracksPage(limit, offset)
        if (summaries.isEmpty()) return@withContext emptyList()
        val clips = dao.clipsFor(summaries.map { it.audioId }).groupBy { it.audioId }
        summaries.map { summary -> ClipHistoryGroup(summary, clips[summary.audioId].orEmpty()) }
    }
}
