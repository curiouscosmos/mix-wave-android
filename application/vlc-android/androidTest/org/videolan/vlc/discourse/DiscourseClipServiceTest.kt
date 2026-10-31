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
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DiscourseClipServiceTest {
    @Suppress("DEPRECATION")
    @Test fun backgroundExportReusesCompletedDownloadAndPublishesClip() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.getExternalFilesDir("discourses"), "$id.audio")
        source.parentFile!!.mkdirs()
        val settings = Settings.getInstance(context)
        val previous = settings.getStringSet("osho_api_discourse_downloads", emptySet()).orEmpty().toSet()
        val manager = context.getSystemService(DownloadManager::class.java)
        var downloadId = -1L
        try {
            instrumentation.context.assets.open("clips/variable.mp3").use { input -> source.outputStream().use { input.copyTo(it) } }
            downloadId = manager.addCompletedDownload("Clip test source", "Synthetic test", false, "audio/mpeg", source.path, source.length(), true)
            settings.edit().putStringSet("osho_api_discourse_downloads", previous + "$id|$downloadId").commit()
            val audio = DiscourseAudio(id, "clip-test", "Synthetic test", null, "english", "Clip test $id",
                "https://example.invalid/unused.mp3", 6.0, source.length(), "audio/mpeg", 1, "", "")
            assertEquals(DiscourseDownloadState.DOWNLOADED, DiscourseDownloadStore(context).state(audio))
            instrumentation.uiAutomation.adoptShellPermissionIdentity()
            assertTrue(DiscourseClipService.start(context, audio, ClipRange(2_000, 4_000)))
            // The request is persisted before starting the service and removed only after completion.
            val preferences = context.getSharedPreferences("discourse_clip_export", Context.MODE_PRIVATE)
            withTimeout(30_000) { while (preferences.contains("request")) delay(100) }
            assertEquals(R.string.clip_saved, DiscourseClipService.status.value?.message)
            assertTrue(source.isFile)
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("Clip test $id%"), null)!!.use { cursor ->
                assertTrue("No public clip was published", cursor.moveToFirst())
                assertTrue(cursor.getLong(1) > 0)
                assertEquals(0, cursor.getInt(2))
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                context.contentResolver.delete(uri, null, null)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            settings.edit().putStringSet("osho_api_discourse_downloads", previous).commit()
            if (downloadId >= 0) manager.remove(downloadId)
            source.delete()
        }
    }
}
