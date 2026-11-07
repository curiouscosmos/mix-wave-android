package org.videolan.mobile.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.playDiscourseAudios
import org.videolan.vlc.gui.audio.DiscourseDetailActivity

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class DailyAlertPlaybackTest {
    private val discourse = Discourse("discourse", "Discourse", null, false, "hindi", null, "", "")
    private val tracks = listOf("first", "target", "last").map { id ->
        DiscourseAudio(id, discourse.id, discourse.title, null, "hindi", id,
            "https://example.test/$id.mp3", 120.0, null, null, null, "", "")
    }

    @Before
    fun setup() {
        mockkStatic("org.videolan.vlc.discourse.DiscoursePlaybackKt")
        every { any<Context>().playDiscourseAudios(any(), any(), any()) } just Runs
        mockkStatic(Toast::class)
        every { Toast.makeText(any<Context>(), any<Int>(), any()) } returns mockk(relaxed = true)
    }

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun waitsForTracksThenPlaysTheDiscourseQueueAtTheTargetOnlyOnce() {
        val activity = activity()
        activity.playNotificationTrack(null, failed = false)
        verify(exactly = 0) { any<Context>().playDiscourseAudios(any(), any(), any()) }

        activity.playNotificationTrack(tracks, failed = false)
        activity.playNotificationTrack(tracks, failed = false)

        verify(exactly = 1) { activity.playDiscourseAudios(tracks, 1, startFromBeginning = true) }
    }

    @Test
    fun missingTrackWrongDiscourseAndLoadFailuresNeverReplacePlayback() {
        activity("missing").playNotificationTrack(tracks, failed = false)
        activity().playNotificationTrack(tracks.map { it.copy(discourseId = "other") }, failed = false)
        activity().playNotificationTrack(null, failed = true)

        verify(exactly = 0) { any<Context>().playDiscourseAudios(any(), any(), any()) }
        verify(exactly = 3) { Toast.makeText(any<Context>(), any<Int>(), Toast.LENGTH_LONG) }
    }

    @Test
    fun ordinaryLinksDoNotAutoplayButANewNotificationCanPlayAgain() {
        activity(autoplay = false).playNotificationTrack(tracks, failed = false)
        verify(exactly = 0) { any<Context>().playDiscourseAudios(any(), any(), any()) }

        repeat(2) { activity().playNotificationTrack(tracks, failed = false) }
        verify(exactly = 2) { any<Context>().playDiscourseAudios(tracks, 1, startFromBeginning = true) }
    }

    private fun activity(audioId: String = "target", autoplay: Boolean = true): DiscourseDetailActivity {
        val intent = Intent().putExtra(DiscourseDetailActivity.ARG_DISCOURSE, discourse)
            .putExtra(DiscourseDetailActivity.ARG_AUDIO_ID, audioId)
            .putExtra("detail_autoplay", autoplay)
        return Robolectric.buildActivity(DiscourseDetailActivity::class.java, intent).get()
    }
}
