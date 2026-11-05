package org.videolan.vlc.discourse

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.tools.ErrorReporter
import org.videolan.tools.Settings
import java.io.File
import java.net.URI

private const val CDN_BASE_URL = "https://osho.b-cdn.net/OSHO/"
private const val KEY_DOWNLOADS = "osho_api_discourse_downloads"
private const val KEY_DOWNLOAD_DISCOURSES = "osho_api_discourse_download_discourses"
private val URL_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:.*")
private val downloadsLock = Any()

internal fun resolveDiscourseUrl(value: String?): String? {
    val path = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val absolute = runCatching { URI(path) }.getOrNull()
    if (absolute?.isAbsolute == true) return absolute.takeIf { it.scheme.equals("https", true) }?.toASCIIString()
    if (URL_SCHEME.matches(path.substringBefore('/'))) return null
    return runCatching {
        URI(CDN_BASE_URL).resolve(URI(null, null, path.trimStart('/'), null)).toASCIIString()
    }.getOrNull()
}

enum class DiscourseDownloadState { MISSING, DOWNLOADING, DOWNLOADED, FAILED }

data class DiscourseDownloadProgress(
    val percent: Int?,
    val state: DiscourseDownloadState
)

internal fun isVerifiedDownload(actualSize: Long?, expectedSize: Long?) =
    actualSize != null && actualSize > 0L

internal fun isCompletedDownload(actualSize: Long, successful: Boolean, totalBytes: Long?) =
    actualSize > 0 && successful && (totalBytes == null || totalBytes <= 0 || totalBytes == actualSize)

internal fun shouldEnqueue(state: DiscourseDownloadState) =
    state != DiscourseDownloadState.DOWNLOADED && state != DiscourseDownloadState.DOWNLOADING

internal fun downloadPercent(downloadedBytes: Long?, totalBytes: Long?, fallbackTotalBytes: Long?): Int? {
    val total = totalBytes?.takeIf { it > 0L } ?: fallbackTotalBytes?.takeIf { it > 0L } ?: return null
    return ((downloadedBytes ?: 0L) * 100L / total).toInt().coerceIn(0, 99)
}

class DiscourseDownloadStore(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService<DownloadManager>()!!
    private val settings = Settings.getInstance(context)
    private val reportedFailures = mutableSetOf<Long>()
    private val cache = HashMap<String, Snapshot>()
    private data class Snapshot(val state: DiscourseDownloadState, val progress: DiscourseDownloadProgress)

    /** Cheap values for row binding. Call [refresh] first; missing entries report as not downloaded. */
    fun cachedState(audio: DiscourseAudio): DiscourseDownloadState =
        synchronized(cache) { cache[audio.id]?.state } ?: DiscourseDownloadState.MISSING

    fun cachedProgress(audio: DiscourseAudio): DiscourseDownloadProgress =
        synchronized(cache) { cache[audio.id]?.progress } ?: DiscourseDownloadProgress(null, DiscourseDownloadState.MISSING)

    fun state(audio: DiscourseAudio): DiscourseDownloadState = snapshot(audio).state

    fun progress(audio: DiscourseAudio): DiscourseDownloadProgress = snapshot(audio).progress

    private fun snapshot(audio: DiscourseAudio): Snapshot = synchronized(cache) { cache[audio.id] }
        ?: compute(audio).also { value -> synchronized(cache) { cache[audio.id] = value } }

    /**
     * Recomputes the download state for the supplied tracks on a worker thread, keeping
     * DownloadManager queries off the UI thread. Returns the IDs whose state changed.
     */
    suspend fun refresh(audios: List<DiscourseAudio>): Set<String> = withContext(Dispatchers.IO) {
        val changed = mutableSetOf<String>()
        audios.forEach { audio ->
            val next = compute(audio)
            val previous = synchronized(cache) { cache.put(audio.id, next) }
            if (previous != next) changed.add(audio.id)
        }
        changed
    }

    fun hasActiveDownloads(): Boolean =
        synchronized(cache) { cache.values.any { it.state == DiscourseDownloadState.DOWNLOADING } }

    fun invalidate(audioId: String? = null): Unit = synchronized(cache) {
        if (audioId == null) cache.clear() else cache.remove(audioId)
    }

    fun invalidateDownload(downloadId: Long): Unit = synchronized(cache) {
        downloads().entries.firstOrNull { it.value == downloadId }?.key?.let(cache::remove)
    }

    private fun compute(audio: DiscourseAudio): Snapshot {
        if (verifiedFile(audio) != null)
            return Snapshot(DiscourseDownloadState.DOWNLOADED, DiscourseDownloadProgress(100, DiscourseDownloadState.DOWNLOADED))
        val id = downloads()[audio.id]
            ?: return Snapshot(DiscourseDownloadState.MISSING, DiscourseDownloadProgress(null, DiscourseDownloadState.MISSING))
        val info = downloadInfo(id)
        val state = when (info.status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> DiscourseDownloadState.DOWNLOADING
            else -> DiscourseDownloadState.FAILED.also { reportFailure(audio, id, info) }
        }
        val progress = if (state == DiscourseDownloadState.DOWNLOADING)
            DiscourseDownloadProgress(downloadPercent(info.downloadedBytes, info.totalBytes, audio.fileSize), state)
        else DiscourseDownloadProgress(null, state)
        return Snapshot(state, progress)
    }

    fun playbackUri(audio: DiscourseAudio): Uri? = verifiedFile(audio)?.let(Uri::fromFile)
        ?: resolveDiscourseUrl(audio.audioUrl)?.let(Uri::parse)

    fun downloadedCount(discourseId: String): Int = downloadDiscourses()
        .filterValues { it == discourseId }
        .keys
        .count { File(context.getExternalFilesDir("discourses") ?: context.filesDir, "$it.audio").isFile }

    fun activeDownloadCount(discourseId: String): Int = downloadDiscourses()
        .filterValues { it == discourseId }
        .keys
        .count { id ->
            downloads()[id]?.let { downloadInfo(it).status } in listOf(
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED
            )
        }

    fun isFullyDownloaded(discourseId: String, totalTracks: Int) =
        totalTracks > 0 && downloadedCount(discourseId) >= totalTracks

    fun download(audio: DiscourseAudio): Boolean = synchronized(downloadsLock) {
        if (!shouldEnqueue(state(audio))) return@synchronized true
        val url = resolveDiscourseUrl(audio.audioUrl) ?: return@synchronized false.also {
            ErrorReporter.error(TAG, "Cannot resolve download URL for track ${audio.id} (${audio.title})")
        }
        remove(audio)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(audio.title)
            .setDescription(audio.discourseName)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI)
            .setDestinationInExternalFilesDir(context, "discourses", fileName(audio))
        audio.mimeType?.let(request::setMimeType)
        val id = runCatching { manager.enqueue(request) }
            .onFailure { ErrorReporter.error(TAG, "Failed to enqueue track ${audio.id} (${audio.title})", it) }
            .getOrNull() ?: return@synchronized false
        save(downloads() + (audio.id to id))
        saveDownloadDiscourses(downloadDiscourses() + (audio.id to audio.discourseId))
        synchronized(cache) {
            cache[audio.id] = Snapshot(
                DiscourseDownloadState.DOWNLOADING,
                DiscourseDownloadProgress(null, DiscourseDownloadState.DOWNLOADING)
            )
        }
        true
    }

    fun cancel(audio: DiscourseAudio) = remove(audio)

    fun remove(audio: DiscourseAudio) = synchronized(downloadsLock) {
        val current = downloads().toMutableMap()
        current.remove(audio.id)?.let { manager.remove(it) }
        file(audio).delete()
        save(current)
        saveDownloadDiscourses(downloadDiscourses() - audio.id)
        synchronized(cache) { cache[audio.id] = Snapshot(DiscourseDownloadState.MISSING, DiscourseDownloadProgress(null, DiscourseDownloadState.MISSING)) }
    }

    fun verifiedFile(audio: DiscourseAudio): File? {
        val file = file(audio)
        if (!isVerifiedDownload(file.takeIf(File::isFile)?.length(), audio.fileSize)) return null
        // DownloadManager writes directly into the destination, including while it is incomplete.
        val id = downloads()[audio.id] ?: return null
        val info = downloadInfo(id)
        return file.takeIf {
            isCompletedDownload(file.length(), info.status == DownloadManager.STATUS_SUCCESSFUL, info.totalBytes)
        }
    }

    private fun file(audio: DiscourseAudio) = File(context.getExternalFilesDir("discourses") ?: context.filesDir, fileName(audio))
    private fun fileName(audio: DiscourseAudio) = "${audio.id}.audio"

    private fun reportFailure(audio: DiscourseAudio, id: Long, info: DownloadInfo) {
        if (!reportedFailures.add(id)) return
        ErrorReporter.error(TAG, "Download failed for track ${audio.id} (${audio.title}); status=${info.status}, reason=${info.reason}")
    }

    private data class DownloadInfo(val status: Int, val downloadedBytes: Long?, val totalBytes: Long?, val reason: Int?)

    private fun downloadInfo(id: Long): DownloadInfo = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
        if (!cursor.moveToFirst()) return@use DownloadInfo(DownloadManager.STATUS_FAILED, null, null, null)
        DownloadInfo(
            cursor.int(DownloadManager.COLUMN_STATUS) ?: DownloadManager.STATUS_FAILED,
            cursor.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            cursor.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            cursor.int(DownloadManager.COLUMN_REASON)
        )
    }

    private fun downloads(): Map<String, Long> = settings.getStringSet(KEY_DOWNLOADS, emptySet()).orEmpty().mapNotNull {
        val parts = it.split('|', limit = 2)
        parts.getOrNull(1)?.toLongOrNull()?.let { id -> parts[0] to id }
    }.toMap()

    private fun save(downloads: Map<String, Long>) {
        settings.edit().putStringSet(KEY_DOWNLOADS, downloads.mapTo(mutableSetOf()) { "${it.key}|${it.value}" }).apply()
    }

    private fun downloadDiscourses(): Map<String, String> = settings.getStringSet(KEY_DOWNLOAD_DISCOURSES, emptySet()).orEmpty().mapNotNull {
        val parts = it.split('|', limit = 2)
        parts.getOrNull(1)?.let { discourseId -> parts[0] to discourseId }
    }.toMap()

    private fun saveDownloadDiscourses(downloads: Map<String, String>) {
        settings.edit().putStringSet(KEY_DOWNLOAD_DISCOURSES, downloads.mapTo(mutableSetOf()) { "${it.key}|${it.value}" }).apply()
    }

    private fun Cursor.int(column: String) = getColumnIndex(column).takeIf { it >= 0 }?.let(::getInt)
    private fun Cursor.long(column: String) = getColumnIndex(column).takeIf { it >= 0 }?.let(::getLong)

}

private const val TAG = "DiscourseDownloads"
