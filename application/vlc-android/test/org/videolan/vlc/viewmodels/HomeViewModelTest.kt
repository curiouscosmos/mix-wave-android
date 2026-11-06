package org.videolan.vlc.viewmodels

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.videolan.tools.PLAYBACK_HISTORY
import org.videolan.tools.Settings
import org.videolan.vlc.BaseTest
import org.videolan.vlc.discourse.*

@org.robolectric.annotation.Config(sdk = [28])
class HomeViewModelTest : BaseTest() {
    private fun discourse(id: String) = Discourse(id, id, null, false, "hindi", null, "", "")
    private fun audio() = DiscourseAudio("audio", "discourse", "Discourse", null, "hindi", "Audio",
        "url", null, null, null, 1, "", "")

    @Test fun weeklySectionsPublishIndependentlyAndRefreshWaitsForBoth() {
        val slow = CompletableDeferred<List<Discourse>>()
        var discourseRequests = 0
        var audioRequests = 0
        val model = HomeViewModel(application, discourseLoader = {
            discourseRequests++; slow.await()
        }, audioLoader = { audioRequests++; listOf(audio()) })
        val store = ViewModelStore().apply { put("home", model) }
        try {
            model.loadWeekly()
            assertEquals(listOf(audio()), model.weeklyTracks.value)
            assertTrue(model.weeklyDiscourses.value!!.isEmpty())
            assertTrue(model.refreshing.value!!)
            model.loadWeekly()
            model.loadWeekly(true)
            assertEquals(1, discourseRequests)
            assertEquals(1, audioRequests)
            slow.complete(listOf(discourse("ready")))
            assertEquals("ready", model.weeklyDiscourses.value!!.single().id)
            assertFalse(model.refreshing.value!!)
            model.loadWeekly()
            assertEquals(2, discourseRequests)
        } finally { store.clear() }
    }

    @Test fun historyCancelsOldResultsSkipsUnchangedPublicationsAndClearsWhenDisabled() {
        val settings = Settings.getInstance(context)
        settings.edit().putBoolean(PLAYBACK_HISTORY, true).commit()
        val slow = CompletableDeferred<Pair<List<Discourse>, List<HomeTrack>>>()
        var loads = 0
        val model = HomeViewModel(application, historyLoader = {
            loads++
            if (loads == 1) withContext(NonCancellable) { slow.await() }
            else listOf(discourse("latest")) to emptyList()
        })
        val store = ViewModelStore().apply { put("home", model) }
        var publications = 0
        val observer = androidx.lifecycle.Observer<List<Discourse>> { publications++ }
        model.recentDiscourses.observeForever(observer)
        try {
            model.refreshHistory()
            model.refreshHistory()
            slow.complete(listOf(discourse("obsolete")) to emptyList())
            assertEquals("latest", model.recentDiscourses.value!!.single().id)
            val before = publications
            model.refreshHistory()
            assertEquals(before, publications)
            settings.edit().putBoolean(PLAYBACK_HISTORY, false).commit()
            model.refreshHistory()
            assertTrue(model.recentDiscourses.value!!.isEmpty())
            assertTrue(model.recentTracks.value!!.isEmpty())
        } finally {
            model.recentDiscourses.removeObserver(observer)
            store.clear()
            settings.edit().putBoolean(PLAYBACK_HISTORY, true).commit()
        }
    }

    @Test fun failedWeeklySectionDoesNotBlockTheOtherOrEraseVisibleContent() {
        var fail = false
        val model = HomeViewModel(application, discourseLoader = {
            if (fail) throw IllegalStateException("offline")
            listOf(discourse("saved"))
        }, audioLoader = { listOf(audio()) })
        val store = ViewModelStore().apply { put("home", model) }
        try {
            model.loadWeekly()
            fail = true
            model.loadWeekly(true)
            assertEquals("saved", model.weeklyDiscourses.value!!.single().id)
            assertEquals(listOf(audio()), model.weeklyTracks.value)
            assertFalse(model.refreshing.value!!)
        } finally { store.clear() }
    }
}
