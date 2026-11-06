package org.videolan.vlc.discourse

import android.app.DownloadManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.tools.Settings
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiscourseMissingDownloadTest {
    @Test fun externallyDeletedDownloadFallsBackToStream() = withDownload { store, audio, file, _ ->
        assertEquals(DiscourseDownloadState.DOWNLOADED, store.state(audio))
        val restored = audio.toMediaWrapper(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("file", restored.uri.scheme)
        assertTrue(file.delete())
        assertNull(store.verifiedFile(audio))
        assertEquals(resolveDiscourseUrl(audio.audioUrl), store.playbackUri(audio).toString())
        store.invalidate(audio.id)
        assertNotEquals(DiscourseDownloadState.DOWNLOADED, store.state(audio))
    }

    @Test fun deletedFileCanBeDownloadedAgainDespiteCachedCompletedState() = withDownload { store, audio, file, oldId ->
        assertEquals(DiscourseDownloadState.DOWNLOADED, store.state(audio))
        assertTrue(file.delete())
        assertTrue(store.download(audio))
        val settings = Settings.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val replacement = settings.getStringSet("osho_api_discourse_downloads", emptySet()).orEmpty()
            .single { it.startsWith("${audio.id}|") }.substringAfter('|').toLong()
        assertNotEquals(oldId, replacement)
    }

    @Suppress("DEPRECATION")
    private fun withDownload(check: (DiscourseDownloadStore, DiscourseAudio, File, Long) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings.getInstance(context)
        val previous = settings.getStringSet("osho_api_discourse_downloads", emptySet()).orEmpty().toSet()
        val previousDiscourses = settings.getStringSet("osho_api_discourse_download_discourses", emptySet()).orEmpty().toSet()
        val id = UUID.randomUUID().toString()
        val file = File(context.getExternalFilesDir("discourses"), "$id.audio")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(256) { 1 })
        val manager = context.getSystemService(DownloadManager::class.java)
        val downloadId = manager.addCompletedDownload("Missing file test", "Test", false, "audio/mpeg", file.path, file.length(), true)
        val audio = DiscourseAudio(id, "test", "Test", null, "english", "Test track",
            "https://example.invalid/unused.mp3", 6.0, file.length(), "audio/mpeg", 1, "", "")
        settings.edit().putStringSet("osho_api_discourse_downloads", previous + "$id|$downloadId").commit()
        val store = DiscourseDownloadStore(context)
        try {
            check(store, audio, file, downloadId)
        } finally {
            store.cancel(audio)
            manager.remove(downloadId)
            settings.edit().putStringSet("osho_api_discourse_downloads", previous)
                .putStringSet("osho_api_discourse_download_discourses", previousDiscourses).commit()
            file.delete()
        }
    }
}
