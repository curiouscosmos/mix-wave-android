package org.videolan.vlc.discourse

import android.app.DownloadManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    @Test fun idleSummaryObserverUpdatesWhenADownloadIsRemoved() = withDownload { store, audio, _, _ ->
        runBlocking {
            withTimeout(5_000) {
                val changes = Channel<Set<String>>(Channel.UNLIMITED)
                val observer = launch {
                    store.summaryChanges().collect { if (it.isNotEmpty()) changes.send(it) }
                }
                try {
                    assertTrue("test" in changes.receive())
                    store.remove(audio)
                    assertTrue("test" in changes.receive())
                    assertEquals(0, store.downloadedCount("test"))
                } finally {
                    observer.cancelAndJoin()
                    changes.close()
                }
            }
        }
    }

    @Test fun cardCountsUseCachedSummariesUntilRefreshed() = withDownload { store, _, file, _ ->
        runBlocking {
            assertTrue("test" in store.refreshSummaries())
            assertEquals(1, store.downloadedCount("test"))
            assertEquals(0, store.activeDownloadCount("test"))
            assertEquals(0, store.downloadedCount("unknown"))
            assertTrue(file.delete())
            // Binding a card must not check the filesystem or query DownloadManager again.
            assertEquals(1, store.downloadedCount("test"))
            assertTrue("test" in store.refreshSummaries())
            assertEquals(0, store.downloadedCount("test"))
            assertTrue(store.refreshSummaries().isEmpty())
        }
    }

    @Test fun summariesAggregateTracksAndExcludeIncompleteFiles() = withDownload { store, _, first, _ ->
        withDownload { _, audio, second, _ ->
            runBlocking {
                store.refreshSummaries()
                assertEquals(2, store.downloadedCount("test"))
                first.writeBytes(ByteArray(100))
                store.refreshSummaries()
                assertEquals(1, store.downloadedCount("test"))
                store.remove(audio)
                store.refreshSummaries()
                assertEquals(0, store.downloadedCount("test"))
                assertFalse(second.exists())
            }
        }
    }

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
        settings.edit().putStringSet("osho_api_discourse_download_discourses", previousDiscourses + "$id|test").commit()
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
