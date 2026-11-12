package org.videolan.vlc

import android.app.Application
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.discourse.restoreDiscourseMetadata
import org.videolan.vlc.media.PlaylistManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class AudioMixerRestoreTest {
    @Test
    fun nextDownloadedDiscourseInRestoredQueueRemainsEligibleForMixer() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()
        val playlist = mockk<PlaylistManager>()
        PlaybackService::class.java.getDeclaredField("playlistManager").apply { isAccessible = true }
            .set(service, playlist)
        val tracks = listOf("first", "second").map { id ->
            MLServiceLocator.getAbstractMediaWrapper(Uri.parse("file:///downloads/$id.audio")).apply {
                tag = "osho_discourse:discourse|$id|" + """{"id":"$id","discourse_id":"discourse",
                    "discourse_name":"Discourse","language":"hindi","title":"$id",
                    "audio_url":"https://example.test/$id.mp3","created_at":"","updated_at":""}"""
                restoreDiscourseMetadata()
            }
        }
        // Reproduce queues where only the initially selected item was forced to audio.
        tracks.first().addFlags(MediaWrapper.MEDIA_FORCE_AUDIO)
        every { playlist.player.isVideoPlaying() } returns false
        val canMix = PlaybackService::class.java.getDeclaredMethod("canPlayAudioMixer").apply { isAccessible = true }
        for (track in tracks) {
            every { playlist.getCurrentMedia() } returns track
            assertTrue("Mixer rejected restored track ${track.title}", canMix.invoke(service) as Boolean)
        }
    }

    @Test
    fun restoredAudioCanMixEvenWhenItsTypeIsStreamOrUnknown() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()
        val media = mockk<MediaWrapper>()
        val playlistManager = mockk<PlaylistManager>()
        PlaybackService::class.java.getDeclaredField("playlistManager").apply { isAccessible = true }
            .set(service, playlistManager)
        every { service.playlistManager.getCurrentMedia() } returns media
        every { service.playlistManager.player.isVideoPlaying() } returns false
        every { media.type } returns MediaWrapper.TYPE_STREAM
        every { media.hasFlag(MediaWrapper.MEDIA_FORCE_AUDIO) } returns true
        val canMix = PlaybackService::class.java.getDeclaredMethod("canPlayAudioMixer").apply { isAccessible = true }

        assertTrue(canMix.invoke(service) as Boolean)
        every { media.type } returns MediaWrapper.TYPE_ALL
        assertTrue(canMix.invoke(service) as Boolean)
        every { service.playlistManager.player.isVideoPlaying() } returns true
        assertFalse(canMix.invoke(service) as Boolean)
        every { service.playlistManager.player.isVideoPlaying() } returns false
        every { media.hasFlag(MediaWrapper.MEDIA_FORCE_AUDIO) } returns false
        assertFalse(canMix.invoke(service) as Boolean)
        every { media.type } returns MediaWrapper.TYPE_AUDIO
        assertTrue(canMix.invoke(service) as Boolean)
    }
}
