package org.videolan.vlc.discourse

import android.app.DownloadManager
import android.content.Context
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.audio.NoiseCancellationLevel
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DiscourseClipServiceTest {
    @Test fun backgroundExportReusesCompletedDownloadAndPublishesClip() = checkExport(false)
    @Test fun processedExportPublishesM4aAndPreservesOfflineSource() = checkExport(true)
    @Test fun persistedVideoRequestResumesAndPublishesMp4() = checkExport(true, true)
    @Test fun removedVisualFailsPersistedRequestAndCleansTemporaryFiles() = checkExport(true, true, true)
    @Test fun missingCaptionsPublishesClipWithWarning() = checkExport(false, captions = true)

    @Suppress("DEPRECATION")
    private fun checkExport(processed: Boolean, video: Boolean = false, removedVisual: Boolean = false,
                            captions: Boolean = false) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.getExternalFilesDir("discourses"), "$id.audio")
        source.parentFile!!.mkdirs()
        val settings = Settings.getInstance(context)
        val previous = settings.getStringSet("osho_api_discourse_downloads", emptySet()).orEmpty().toSet()
        val manager = context.getSystemService(DownloadManager::class.java)
        var downloadId = -1L
        val visual = File(context.cacheDir, "service-visual.mp4")
        try {
            instrumentation.context.assets.open("clips/variable.mp3").use { input -> source.outputStream().use { input.copyTo(it) } }
            downloadId = manager.addCompletedDownload("Clip test source", "Synthetic test", false, "audio/mpeg", source.path, source.length(), true)
            settings.edit().putStringSet("osho_api_discourse_downloads", previous + "$id|$downloadId").commit()
            val audio = DiscourseAudio(id, "clip-test", "Synthetic test", null, "english", "Clip test $id",
                "https://example.invalid/unused.mp3", 6.0, source.length(), "audio/mpeg", 1, "", "")
            assertEquals(DiscourseDownloadState.DOWNLOADED, DiscourseDownloadStore(context).state(audio))
            instrumentation.uiAutomation.adoptShellPermissionIdentity()
            var options = if (processed) ClipExportOptions(NoiseCancellationLevel.LOW,
                ClipMixerOptions(android.net.Uri.fromFile(source).toString(), "Background", 37, true)) else ClipExportOptions()
            if (captions) options = options.copy(captionPath = "http://example.invalid/captions.srt")
            // The request is persisted before starting the service and removed only after completion.
            val preferences = context.getSharedPreferences("discourse_clip_export", Context.MODE_PRIVATE)
            if (video) {
                instrumentation.context.assets.open("clips/visual.mp4").use { input -> visual.outputStream().use { input.copyTo(it) } }
                options = options.copy(output = ClipOutput.VIDEO, visualUri = android.net.Uri.fromFile(visual).toString())
                preferences.edit().putString("request", org.json.JSONObject().apply {
                    put("audio", DiscourseClipService.audioAdapter.toJson(audio))
                    put("start", 2_000); put("end", 4_000); put("options", options.toJson())
                }.toString()).commit()
                if (removedVisual) visual.delete()
                // Simulate recovery with only the durable request, without any intent extras.
                androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, DiscourseClipService::class.java))
            } else assertTrue(DiscourseClipService.start(context, audio, ClipRange(2_000, 4_000), options))
            withTimeout(30_000) { while (preferences.contains("request")) delay(100) }
            if (removedVisual) {
                assertEquals(R.string.clip_visual_failed, DiscourseClipService.status.value?.message)
                assertFalse(File(context.cacheDir, "discourse-clip-export.tmp").exists())
                assertFalse(File(context.cacheDir, "discourse-clip-audio.tmp").exists())
                return@runBlocking
            }
            assertEquals(if (captions) R.string.clip_saved_no_captions else R.string.clip_saved,
                DiscourseClipService.status.value?.message)
            assertTrue(source.isFile)
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("Clip test $id%"), null)!!.use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue("No public clip was published", cursor.moveToFirst())
                assertTrue(cursor.getLong(1) > 0)
                assertEquals(0, cursor.getInt(2))
                assertEquals(if (video) "video/mp4" else if (processed) "audio/mp4" else "audio/mpeg", cursor.getString(3))
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                context.contentResolver.delete(uri, null, null)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            settings.edit().putStringSet("osho_api_discourse_downloads", previous).commit()
            if (downloadId >= 0) manager.remove(downloadId)
            source.delete()
            visual.delete()
        }
    }
}
