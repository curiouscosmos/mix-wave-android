package org.videolan.vlc

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.media.PlaylistManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class AudioMixerRestoreTest {
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
