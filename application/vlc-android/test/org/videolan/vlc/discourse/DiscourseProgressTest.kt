package org.videolan.vlc.discourse

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DiscourseProgressTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var store: DiscoursePlaybackStore

    @Before
    fun setup() {
        preferences = newPreferences()
        store = DiscoursePlaybackStore(preferences)
    }

    // An editor-backed map lets a fresh index reload the same persisted values.
    private fun newPreferences(): SharedPreferences {
        val values = HashMap<String, Any>()
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String>(); editor }
        every { editor.putStringSet(any(), any()) } answers { values[firstArg()] = secondArg<Set<String>>().toSet(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        every { editor.clear() } answers { values.clear(); editor }
        every { editor.apply() } returns Unit
        return mockk {
            every { all } answers { values.toMap() }
            every { edit() } returns editor
            every { getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { getStringSet(any(), any()) } answers {
                ((values[firstArg()] as? Set<String>) ?: secondArg<Set<String>>()).toMutableSet()
            }
        }
    }

    @Test
    fun progressFollowsBackwardSeeksAndClamps() {
        store.register("seek", "seek-discourse", 1000)
        store.save("seek", 500)
        assertEquals(50, store.audioProgress("seek"))
        store.save("seek", 100)
        assertEquals(10, store.audioProgress("seek"))
        store.save("seek", 2000)
        assertEquals(100, store.audioProgress("seek"))
        store.save("seek", -10)
        assertEquals(0, store.audioProgress("seek"))
    }

    @Test
    fun checkpointUsesElapsedTimeAndResetsAfterImmediateFlushes() {
        assertEquals(false, discourseCheckpointDue(1000, 30_999))
        assertEquals(true, discourseCheckpointDue(1000, 31_000))
        assertEquals(true, discourseCheckpointDue(1000, 100_000))
        assertEquals(false, discourseCheckpointDue(31_000, 31_001))
        assertEquals(false, discourseCheckpointDue(31_001, 61_000))
        assertEquals(true, discourseCheckpointDue(31_001, 61_001))
    }

    @Test
    fun completedTrackStaysFullDuringReplayAndDoesNotImplyPlayed() {
        store.register("complete", "complete-discourse", 1000)
        store.complete("complete")
        store.clear("complete")
        assertNull(store.position("complete"))
        store.save("complete", 100)
        assertEquals(100, store.audioProgress("complete"))
        assertEquals(false, store.isPlayed("complete"))
        val preferences = this.preferences
        val restored = DiscoursePlaybackStore(object : SharedPreferences by preferences {})
        runBlocking { restored.initialize() }
        assertEquals(100, restored.audioProgress("complete"))
        assertEquals(100, restored.discourseProgress("complete-discourse", 1))
        assertEquals(true, preferences.getStringSet("osho_progress_completed", emptySet())!!.contains("complete"))
    }

    @Test
    fun unknownDurationContributesZeroUntilMetadataArrives() {
        store.register("invalid", "unknown-discourse", -1)
        store.save("invalid", 500)
        assertEquals(0, store.audioProgress("invalid"))
        store.save("unknown", 500)
        store.register("unknown", "unknown-discourse", 0)
        assertEquals(0, store.audioProgress("unknown"))
        store.register("unknown", "unknown-discourse", 1000)
        assertEquals(50, store.audioProgress("unknown"))
        store.register("unknown", "unknown-discourse", -1)
        assertEquals(50, store.audioProgress("unknown"))
        store.complete("no-duration")
        assertEquals(100, store.audioProgress("no-duration"))
    }

    @Test
    fun changesIdentifyOnlyAffectedAudioAndDiscourseMembership() = runBlocking {
        val changes = ArrayList<DiscoursePlaybackStore.Change>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { store.changes.take(3).toList(changes) }
        store.register("events", "original", 1000)
        store.save("events", 500)
        store.register("events", "replacement", 1000)
        collector.join()
        assertEquals(listOf("events", "events", "events"), changes.map { it.audioId })
        assertEquals(setOf("original"), changes[1].discourseIds)
        assertEquals(setOf("original", "replacement"), changes[2].discourseIds)
    }

    @Test
    fun discourseUsesAllTracksAndReconcilesMembership() {
        store.register("aggregate-one", "aggregate", 1000)
        store.register("aggregate-two", "aggregate", 1000)
        store.register("aggregate-half", "aggregate", 1000)
        store.complete("aggregate-one")
        store.complete("aggregate-two")
        store.save("aggregate-half", 500)
        assertEquals(25, store.discourseProgress("aggregate", 10))
        assertEquals(0, store.discourseProgress("aggregate", 0))
        store.register(listOf(audio("aggregate-half").copy(discourseId = "aggregate", durationSeconds = 1.0)), "aggregate")
        assertEquals(50, store.discourseProgress("aggregate", 1))
        store.register("aggregate-half", "moved", 1000)
        assertEquals(0, store.discourseProgress("aggregate", 1))
        assertEquals(50, store.discourseProgress("moved", 1))
    }

    @Test
    fun legacyPositionIsBackfilledWithoutInferringCompletion() = runBlocking {
        val preferences = newPreferences()
        preferences.edit().clear().putLong("osho_api_discourse_playback_position.legacy", 500).apply()
        val restored = DiscoursePlaybackStore(object : SharedPreferences by preferences {})
        restored.initialize()
        restored.register("legacy", "legacy-discourse", 1000)
        assertEquals(50, restored.audioProgress("legacy"))
        restored.markPlayed("legacy")
        restored.clear("legacy")
        assertEquals(0, restored.audioProgress("legacy"))
    }

    private fun audio(id: String) = DiscourseAudio(
        id = id,
        discourseId = "discourse-$id",
        discourseName = "Discourse $id",
        discourseThumbnailUrl = null,
        language = "hindi",
        title = "Track $id",
        audioUrl = "https://example.test/$id.mp3",
        durationSeconds = 120.0,
        fileSize = 100L,
        mimeType = "audio/mpeg",
        trackNumber = 1,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-02T00:00:00Z"
    )
}
