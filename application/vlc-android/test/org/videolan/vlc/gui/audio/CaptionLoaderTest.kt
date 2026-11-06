package org.videolan.vlc.gui.audio

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.videolan.vlc.discourse.*

class CaptionLoaderTest {
    private fun audio(id: String = "a", captions: Boolean = false) = DiscourseAudio(
        id, "discourse", "Discourse", null, "hindi", id, "audio", null, null, null, 1, "", "",
        hasCc = captions, ccPath = if (captions) "captions" else null)

    @Test fun selectionIsLazyAndFirstOpeningDiscoversNewCaptions() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        var requests = 0
        var transcripts = 0
        var result = emptyList<CaptionCue>()
        val loader = CaptionLoader(scope, { requests++; it.copy(hasCc = true, ccPath = "new") }, {
            transcripts++; listOf(CaptionCue(0, 1000, "hello"))
        }) { _, cues, error -> assertFalse(error); result = cues.orEmpty() }
        loader.select(audio())
        assertEquals(0, requests)
        assertTrue(loader.request())
        assertEquals(1, requests)
        assertEquals(1, result.size)
        assertFalse(loader.request())
        assertEquals(1, transcripts)
        assertTrue(loader.request(retry = true))
        assertEquals(2, requests)
        scope.cancel()
    }

    @Test fun concurrentOpeningsAreDeduplicatedAndOldSelectionsCannotPublish() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val response = CompletableDeferred<DiscourseAudio>()
        val published = mutableListOf<String>()
        var requests = 0
        val loader = CaptionLoader(scope, {
            requests++
            // Simulate a transport that finishes even after cancellation.
            withContext(NonCancellable) { response.await() }
        }, { emptyList() }) { item, _, _ -> published += item.id }
        loader.select(audio())
        loader.request()
        assertFalse(loader.request())
        assertEquals(1, requests)
        loader.select(audio("b"))
        response.complete(audio())
        assertTrue(published.isEmpty())
        loader.request()
        assertEquals(listOf("b"), published)
        scope.cancel()
    }

    @Test fun offlineRefreshUsesSavedMetadataAndErrorsCanBeRetried() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        var transcriptFails = false
        val results = mutableListOf<Boolean>()
        val loader = CaptionLoader(scope, { throw IllegalStateException("offline") }, {
            if (transcriptFails) throw IllegalStateException("missing cached transcript")
            listOf(CaptionCue(0, 1000, "cached"))
        }) { _, cues, error -> results += error; if (!error) assertEquals("cached", cues!!.single().text) }
        loader.select(audio(captions = true))
        loader.request()
        transcriptFails = true
        loader.request(true)
        assertFalse(loader.request())
        transcriptFails = false
        loader.request(true)
        assertEquals(listOf(false, true, false), results)
        scope.cancel()
    }
}
