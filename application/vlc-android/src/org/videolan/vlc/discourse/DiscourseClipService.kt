package org.videolan.vlc.discourse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import org.videolan.tools.ErrorReporter
import org.videolan.vlc.R
import java.io.File
import java.util.UUID

internal data class ClipExportStatus(val message: Int, val percent: Int? = null, val running: Boolean = true)

/** One persisted export at a time; DownloadManager remains the owner of full-track downloads. */
class DiscourseClipService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var finished = false
    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:clip-export")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.clip_download), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            job?.cancel()
            if (job == null) finish()
            return START_NOT_STICKY
        }
        val notification = notification(getString(R.string.clip_preparing)).build()
        mutableStatus.value = ClipExportStatus(R.string.clip_preparing)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification)
        if (job == null) {
            finished = false
            job = scope.launch {
                var processing = false
                try {
                    cleanupOutput()
                    val request = JSONObject(preferences.getString(REQUEST, null) ?: return@launch)
                    val audio = audioAdapter.fromJson(request.getString("audio")) ?: error("Missing audio")
                    val range = ClipRange(request.getLong("start"), request.getLong("end"))
                    val options = ClipExportOptions.fromJson(request.optJSONObject("options"))
                    processing = options.processed
                    require(range.startMs >= 0 && range.endMs - range.startMs >= 1_000)
                    val downloads = DiscourseDownloadStore(this@DiscourseClipService)
                    if (processing) check(downloads.verifiedFile(audio) != null) { "Offline discourse is no longer available" }
                    check(downloads.download(audio)) { "Cannot download source" }
                    var source = downloads.verifiedFile(audio)
                    while (source == null) {
                        ensureActive()
                        val progress = downloads.progress(audio)
                        check(progress.state != DiscourseDownloadState.FAILED) { "Source download failed" }
                        mutableStatus.value = ClipExportStatus(R.string.clip_downloading_source, progress.percent)
                        notifications.notify(NOTIFICATION, notification(getString(R.string.clip_downloading_source), progress.percent).build())
                        delay(1_000)
                        source = downloads.verifiedFile(audio)
                    }
                    wakeLock.acquire(10 * 60 * 1_000L)
                    val temporary = File(cacheDir, "discourse-clip-export.tmp")
                    val message = if (processing) R.string.clip_processing else R.string.clip_cutting
                    val onProgress: (Int) -> Unit = { percent ->
                        wakeLock.acquire(10 * 60 * 1_000L)
                        mutableStatus.value = ClipExportStatus(message, percent)
                        notifications.notify(NOTIFICATION, notification(getString(message), percent).build())
                    }
                    val mime = if (processing) ProcessedClipExporter.export(this@DiscourseClipService, source, range, options, temporary, onProgress)
                        else DiscourseClipExporter.export(source, range, temporary, onProgress)
                    ensureActive()
                    val uri = publish(temporary, audio.title, range, mime)
                    val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    val completed = NotificationCompat.Builder(this@DiscourseClipService, CHANNEL)
                        .setSmallIcon(R.drawable.ic_player_clip).setContentTitle(getString(R.string.clip_saved))
                        .setContentText(audio.title).setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(this@DiscourseClipService, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                        .build()
                    notifications.notify(RESULT_NOTIFICATION, completed)
                    mutableStatus.value = ClipExportStatus(R.string.clip_saved, running = false)
                    mutableResults.tryEmit(R.string.clip_saved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportFailure(error, processing)
                } catch (error: LinkageError) {
                    reportFailure(error, processing)
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                    cleanupOutput()
                    File(cacheDir, "discourse-clip-export.tmp").delete()
                    finish()
                }
            }
        }
        return START_STICKY
    }

    private fun reportFailure(error: Throwable, processing: Boolean) {
        ErrorReporter.error("DiscourseClip", "Clip export failed", error)
        val message = if (processing) {
            if (error is UnsupportedClipFormat) R.string.clip_processing_unsupported else R.string.clip_processing_failed
        } else if (error is UnsupportedClipFormat) R.string.clip_unsupported else R.string.clip_failed
        mutableStatus.value = ClipExportStatus(message, running = false)
        mutableResults.tryEmit(message)
        notifications.notify(RESULT_NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_player_clip).setContentTitle(getString(message))
            .setContentText(getString(R.string.clip_retry)).setAutoCancel(true).build())
    }

    private fun notification(message: String, percent: Int? = null): NotificationCompat.Builder {
        val cancel = PendingIntent.getService(this, 0, Intent(this, DiscourseClipService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_player_clip)
            .setContentTitle(getString(R.string.clip_download)).setContentText(message)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, percent ?: 0, percent == null)
            .addAction(0, getString(android.R.string.cancel), cancel)
    }

    private suspend fun publish(source: File, title: String, range: ClipRange, mime: String): Uri {
        val extension = if (mime == "audio/mpeg") "mp3" else "m4a"
        val safeTitle = title.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").take(80).ifBlank { "Discourse" }
        val name = "${safeTitle}_${clipTimestamp(range.startMs).replace(':', '-')}-${clipTimestamp(range.endMs).replace(':', '-')}_${UUID.randomUUID().toString().take(8)}.$extension"
        val uri: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Rajneesh Discourse")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Cannot create clip")
            if (!preferences.edit().putString(OUTPUT_URI, uri.toString()).commit()) {
                contentResolver.delete(uri, null, null)
                error("Cannot persist output")
            }
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                }
            } ?: error("Cannot write clip")
            check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Rajneesh Discourse")
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, name)
            val partial = File(directory, "$name.partial")
            check(preferences.edit().putString(OUTPUT_FILE, partial.path).commit()) { "Cannot persist output" }
            source.inputStream().use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    output.write(buffer, 0, size)
                }
            } }
            check(partial.renameTo(file))
            android.media.MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf(mime), null)
            uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        }
        check(preferences.edit().remove(OUTPUT_URI).remove(OUTPUT_FILE).commit()) { "Cannot finalize output" }
        return uri
    }

    private fun cleanupOutput() {
        preferences.getString(OUTPUT_URI, null)?.let { runCatching { contentResolver.delete(Uri.parse(it), null, null) } }
        preferences.getString(OUTPUT_FILE, null)?.let { File(it).delete() }
        preferences.edit().remove(OUTPUT_URI).remove(OUTPUT_FILE).commit()
    }

    private fun finish() = synchronized(Companion) {
        if (!finished) {
            finished = true
            if (mutableStatus.value?.running == true) mutableStatus.value = null
            preferences.edit().remove(REQUEST).commit()
            job = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "discourse_clip_export"
        private const val REQUEST = "request"
        private const val OUTPUT_URI = "output_uri"
        private const val OUTPUT_FILE = "output_file"
        private const val CHANNEL = "discourse_clips"
        private const val NOTIFICATION = 1041
        private const val RESULT_NOTIFICATION = 1042
        private const val CANCEL = "cancel_clip_export"
        internal val audioAdapter by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(DiscourseAudio::class.java) }
        private val mutableStatus = MutableStateFlow<ClipExportStatus?>(null)
        internal val status = mutableStatus.asStateFlow()
        private val mutableResults = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        internal val results = mutableResults.asSharedFlow()

        internal fun cancel(context: Context) {
            context.startService(Intent(context, DiscourseClipService::class.java).setAction(CANCEL))
        }

        /** Returns false if another export is already active. */
        @Synchronized
        internal fun start(context: Context, audio: DiscourseAudio, range: ClipRange, options: ClipExportOptions = ClipExportOptions()): Boolean {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (preferences.contains(REQUEST)) {
                // Also wakes a persisted request after an interrupted process restart.
                ContextCompat.startForegroundService(context, Intent(context, DiscourseClipService::class.java))
                return false
            }
            check(preferences.edit().putString(REQUEST, JSONObject().apply {
                put("audio", audioAdapter.toJson(audio)); put("start", range.startMs); put("end", range.endMs)
                put("options", options.toJson())
            }.toString()).commit())
            try {
                mutableStatus.value = ClipExportStatus(R.string.clip_preparing)
                ContextCompat.startForegroundService(context, Intent(context, DiscourseClipService::class.java))
            } catch (error: Exception) {
                mutableStatus.value = null
                preferences.edit().remove(REQUEST).commit()
                throw error
            }
            return true
        }
    }
}
