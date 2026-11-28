package org.videolan.vlc.bookmarks

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: BookmarkStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = context.getSharedPreferences("bookmark-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = BookmarkStore(prefs) { clock++ }
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun track(key: String, kind: BookmarkTrackKind = BookmarkTrackKind.LOCAL) = BookmarkedTrack(
        key = key,
        kind = kind,
        title = "Track $key",
        subtitle = "Osho",
        artwork = null,
        durationMs = 60_000L,
        uri = if (kind == BookmarkTrackKind.LOCAL) "file:///$key.mp3" else null
    )

    @Test
    fun capsEachTrackAtTwentyStamps() {
        val value = track("one")
        for (index in 1..20) {
            assertEquals(BookmarkAddOutcome.ADDED, store.add(value, index * 1_000L).outcome)
        }
        assertEquals(20, store.countFor("one"))
        assertEquals(BookmarkAddOutcome.LIMIT_REACHED, store.add(value, 21_000L).outcome)
        assertEquals(20, store.countFor("one"))
    }

    @Test
    fun ignoresStampWithinOneSecondOfAnother() {
        val value = track("one")
        assertEquals(BookmarkAddOutcome.ADDED, store.add(value, 5_000L).outcome)
        assertEquals(BookmarkAddOutcome.DUPLICATE, store.add(value, 5_500L).outcome)
        assertEquals(1, store.countFor("one"))
    }

    @Test
    fun pagesByTrackAndReturnsAllStamps() {
        for (index in 1..30) {
            val value = track("track-$index")
            store.add(value, 1_000L)
        }
        store.add(track("track-30"), 5_000L)

        val first = store.page(0)
        val second = store.page(24)
        assertEquals(BookmarkStore.PAGE_SIZE, first.size)
        assertEquals(6, second.size)
        assertEquals(30, store.count())
        assertEquals(listOf("track-30", "track-29"), first.take(2).map { it.key })
        assertEquals(2, first.first().stamps.size)
    }

    @Test
    fun persistsAndRestoresStamps() {
        assertEquals(BookmarkAddOutcome.ADDED, store.add(track("one"), 12_000L).outcome)
        val restored = BookmarkStore(prefs)
        val value = restored.state.value.single()
        assertEquals("one", value.key)
        assertEquals(12_000L, value.stamps.single().positionMs)
    }

    @Test
    fun deletingLastStampRemovesTheTrack() {
        val value = track("one")
        store.add(value, 12_000L)
        assertTrue(store.remove("one", 12_000L))
        assertTrue(store.state.value.isEmpty())
        assertEquals(0, store.countFor("one"))
    }

    @Test
    fun discourseTracksKeepTheirAudioSnapshot() {
        val audio = org.videolan.vlc.discourse.DiscourseAudio(
            "audio", "discourse", "Discourse", null, "english", "Title",
            "https://example.com/audio.mp3", 60.0, null, null, 1, "", ""
        )
        val value = track("discourse:audio", BookmarkTrackKind.DISCOURSE).copy(audio = audio)
        assertEquals(BookmarkAddOutcome.ADDED, store.add(value, 1_000L).outcome)
        val restored = BookmarkStore(prefs).state.value.single()
        assertNotNull(restored.audio)
        assertEquals(audio, restored.audio)
    }
}
