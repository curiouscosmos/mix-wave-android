package org.videolan.vlc.discourse

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscourseFavouritesStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: DiscourseFavouritesStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = context.getSharedPreferences("favourites-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = DiscourseFavouritesStore(prefs)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun audio(id: String, discourseId: String = "discourse") = DiscourseAudio(
        id, discourseId, "Discourse", null, "english", "Track $id",
        "https://example.com/$id.mp3", 60.0, null, null, 1, "", ""
    )

    private fun discourse(id: String) = Discourse(
        id, "Discourse $id", null, false, "english", null, "", ""
    )

    @Test
    fun likedItemsCreatePlaylistsAndSurviveRestart() {
        store.addTrack(audio("audio-a"))
        store.addDiscourse(discourse("discourse-a"))

        assertEquals(listOf("audio-a"), store.state.value.tracks.map { it.audio.id })
        assertEquals(listOf("discourse-a"), store.state.value.discourses.map { it.discourse.id })

        val reloaded = DiscourseFavouritesStore(prefs)
        assertEquals(listOf("audio-a"), reloaded.state.value.tracks.map { it.audio.id })
        assertEquals(listOf("discourse-a"), reloaded.state.value.discourses.map { it.discourse.id })

        store.removeTrack("audio-a")
        store.removeDiscourse("discourse-a")
        assertTrue(store.state.value.tracks.isEmpty())
        assertTrue(store.state.value.discourses.isEmpty())
        assertTrue(DiscourseFavouritesStore(prefs).state.value.tracks.isEmpty())
    }

    @Test
    fun newerItemsComeFirstAndAreNotDuplicated() {
        store.addTrack(audio("first"))
        store.addTrack(audio("second"))
        store.addTrack(audio("first"))

        assertEquals(listOf("first", "second"), store.state.value.tracks.map { it.audio.id })
    }

    @Test
    fun reconcileRemovesUnlikedItemsAndHydratesKnownRecords() {
        store.addTrack(audio("audio-a"))
        store.addTrack(audio("audio-b"))
        store.addDiscourse(discourse("discourse-a"))
        store.addDiscourse(discourse("discourse-b"))

        store.reconcile(
            likedDiscourseIds = setOf("discourse-a", "discourse-c"),
            likedAudioIds = setOf("audio-a", "audio-c"),
            recentDiscourses = listOf(discourse("discourse-c")),
            recentAudios = listOf(audio("audio-c"))
        )

        assertEquals(listOf("audio-a", "audio-c"), store.state.value.tracks.map { it.audio.id })
        assertEquals(listOf("discourse-a", "discourse-c"), store.state.value.discourses.map { it.discourse.id })
    }
}
