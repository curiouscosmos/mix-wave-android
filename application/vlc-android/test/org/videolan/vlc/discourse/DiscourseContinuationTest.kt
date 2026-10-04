package org.videolan.vlc.discourse

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscourseContinuationTest {
    private fun audio(id: String, discourse: String = "discourse", number: Int = 1) = DiscourseAudio(
        id, discourse, "Discourse", null, "english", id, "https://example.com/$id",
        null, null, null, number, "", ""
    )

    private fun continuation(tracks: List<DiscourseAudio>, selected: String) =
        discourseContinuation(tracks, DiscoursePlaybackIds("discourse", selected)).map { it.id }

    private val tracks = listOf(audio("first"), audio("middle"), audio("last"))

    @Test fun firstTrackQueuesRemainingTracks() {
        assertEquals(listOf("middle", "last"), continuation(tracks, "first"))
    }

    @Test fun middleTrackQueuesOnlyLaterTracks() {
        assertEquals(listOf("last"), continuation(tracks, "middle"))
    }

    @Test fun finalOrMissingTrackQueuesNothing() {
        assertEquals(emptyList<String>(), continuation(tracks, "last"))
        assertEquals(emptyList<String>(), continuation(tracks, "missing"))
        assertEquals(emptyList<String>(), continuation(emptyList(), "first"))
    }

    @Test fun duplicatesAndOtherDiscoursesAreExcludedWithoutSorting() {
        val response = listOf(audio("earlier"), audio("first"), audio("later", number = 9),
            audio("first"), audio("foreign", "other"), audio("later"), audio("last", number = 2))
        assertEquals(listOf("later", "last"), continuation(response, "first"))
        assertEquals(emptyList<String>(), continuation(listOf(audio("first", "other")), "first"))
    }
}
