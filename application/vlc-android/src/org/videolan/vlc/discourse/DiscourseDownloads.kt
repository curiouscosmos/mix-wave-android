package org.videolan.vlc.discourse

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.content.getSystemService
import org.videolan.tools.Settings
import java.io.File
import java.net.URI

private const val CDN_BASE_URL = "https://osho.b-cdn.net/OSHO/"
private const val KEY_DOWNLOADS = "osho_api_discourse_downloads"
private const val KEY_DOWNLOAD_DISCOURSES = "osho_api_discourse_download_discourses"
private val URL_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:.*")

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
    val indeterminate: Boolean,
    val state: DiscourseDownloadState
)

internal fun isVerifiedDownload(actualSize: Long?, expectedSize: Long?) =
    actualSize != null && (expectedSize == null || expectedSize <= 0L || actualSize == expectedSize)

internal fun shouldEnqueue(state: DiscourseDownloadState) =
    state != DiscourseDownloadState.DOWNLOADED && state != DiscourseDownloadState.DOWNLOADING

class DiscourseDownloadStore(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService<DownloadManager>()!!
    private val settings = Settings.getInstance(context)

    fun state(audio: DiscourseAudio): DiscourseDownloadState {
        if (verifiedFile(audio) != null) return DiscourseDownloadState.DOWNLOADED
        val id = downloads()[audio.id] ?: return DiscourseDownloadState.MISSING
        return when (downloadInfo(id).status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> DiscourseDownloadState.DOWNLOADING
            DownloadManager.STATUS_SUCCESSFUL -> DiscourseDownloadState.FAILED
            else -> DiscourseDownloadState.FAILED
        }
    }

    fun progress(audio: DiscourseAudio): DiscourseDownloadProgress {
        if (verifiedFile(audio) != null) return DiscourseDownloadProgress(100, false, DiscourseDownloadState.DOWNLOADED)
        val id = downloads()[audio.id] ?: return DiscourseDownloadProgress(null, false, DiscourseDownloadState.MISSING)
        val info = downloadInfo(id)
        val state = when (info.status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> DiscourseDownloadState.DOWNLOADING
            else -> DiscourseDownloadState.FAILED
        }
        if (state != DiscourseDownloadState.DOWNLOADING) return DiscourseDownloadProgress(null, false, state)
        val total = info.totalBytes
        val percent = if (total != null && total > 0L) ((info.downloadedBytes ?: 0L) * 100L / total).toInt().coerceIn(0, 99) else null
        return DiscourseDownloadProgress(percent, percent == null, state)
    }

    fun playbackUri(audio: DiscourseAudio): Uri? = verifiedFile(audio)?.let(Uri::fromFile)
        ?: resolveDiscourseUrl(audio.audioUrl)?.let(Uri::parse)

    fun downloadedCount(discourseId: String): Int = downloadDiscourses()
        .filterValues { it == discourseId }
        .keys
        .count { File(context.getExternalFilesDir("discourses") ?: context.filesDir, "$it.audio").isFile }

    fun knownDownloadCount(discourseId: String): Int = downloadDiscourses()
        .filterValues { it == discourseId }
        .keys
        .count { id ->
            val file = File(context.getExternalFilesDir("discourses") ?: context.filesDir, "$id.audio")
            file.isFile || downloads()[id]?.let { downloadInfo(it).status } in listOf(
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED
            )
        }

    fun isFullyDownloaded(discourseId: String, totalTracks: Int) =
        totalTracks > 0 && downloadedCount(discourseId) >= totalTracks

    fun download(audio: DiscourseAudio): Boolean {
        if (!shouldEnqueue(state(audio))) return true
        val url = resolveDiscourseUrl(audio.audioUrl) ?: return false
        remove(audio)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(audio.title)
            .setDescription(audio.discourseName)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI)
            .setDestinationInExternalFilesDir(context, "discourses", fileName(audio))
        audio.mimeType?.let(request::setMimeType)
        val id = runCatching { manager.enqueue(request) }.getOrNull() ?: return false
        save(downloads() + (audio.id to id))
        saveDownloadDiscourses(downloadDiscourses() + (audio.id to audio.discourseId))
        return true
    }

    fun cancel(audio: DiscourseAudio) = remove(audio)

    fun remove(audio: DiscourseAudio) {
        val current = downloads().toMutableMap()
        current.remove(audio.id)?.let { manager.remove(it) }
        file(audio).delete()
        save(current)
        saveDownloadDiscourses(downloadDiscourses() - audio.id)
    }

    private fun verifiedFile(audio: DiscourseAudio): File? = file(audio).takeIf { isVerifiedDownload(it.takeIf(File::isFile)?.length(), audio.fileSize) }

    private fun file(audio: DiscourseAudio) = File(context.getExternalFilesDir("discourses") ?: context.filesDir, fileName(audio))
    private fun fileName(audio: DiscourseAudio) = "${audio.id}.audio"

    private data class DownloadInfo(val status: Int, val downloadedBytes: Long?, val totalBytes: Long?)

    private fun downloadInfo(id: Long): DownloadInfo = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
        if (!cursor.moveToFirst()) return@use DownloadInfo(DownloadManager.STATUS_FAILED, null, null)
        DownloadInfo(
            cursor.int(DownloadManager.COLUMN_STATUS) ?: DownloadManager.STATUS_FAILED,
            cursor.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            cursor.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
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
