package org.videolan.vlc

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.ILibVLC
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.media.PlaylistManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class AudioMixerTransitionTest {
    @Test
    fun trackEndAndInputStopKeepMixerRunning() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()
        val playlist = mockk<PlaylistManager>(relaxed = true)
        val mixer = mockk<TestMediaPlayer>(relaxed = true)
        val mainPlayer = mockk<TestMediaPlayer>(relaxed = true)
        every { playlist.player.mediaplayer } returns mainPlayer
        setField(service, "playlistManager", playlist)
        setField(service, "mixerPlayer", mixer)
        val events = Channel<Any>(Channel.UNLIMITED)
        setField(service, "cbActor", events)
        every { playlist.hasNext() } returns true
        every { playlist.stopAfter } returns -1

        for (type in listOf(MediaPlayer.Event.EndReached, MediaPlayer.Event.Stopped)) {
            val event = MediaPlayer.Event::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(type)
            service.onMediaPlayerEvent(event)
        }

        verify(exactly = 0) { mixer.stop() }
        verify(exactly = 0) { mixer.pause() }
        events.close()
    }

    @Test
    fun nextTrackPlayingDoesNotRestartOrFadeAnAlreadyPlayingMixer() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()
        val playlist = mockk<PlaylistManager>(relaxed = true)
        val media = mockk<MediaWrapper>()
        val mixer = mockk<TestMediaPlayer>(relaxed = true)
        setField(service, "playlistManager", playlist)
        setField(service, "mixerPlayer", mixer)
        every { playlist.getCurrentMedia() } returns media
        every { playlist.player.isVideoPlaying() } returns false
        every { media.type } returns MediaWrapper.TYPE_AUDIO
        every { mixer.isPlaying } returns true

        PlaybackService::class.java.getDeclaredMethod("playAudioMixer", MediaWrapper::class.java)
            .apply { isAccessible = true }.invoke(service, media)

        verify(exactly = 0) { mixer.play() }
        verify(exactly = 0) { mixer.setVolume(any()) }
    }

    private fun setField(service: PlaybackService, name: String, value: Any) {
        PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @Test
    fun explicitPauseAndStopStillControlMixer() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()
        val mixer = mockk<TestMediaPlayer>(relaxed = true)
        setField(service, "mixerPlayer", mixer)

        PlaybackService::class.java.getDeclaredMethod("pauseAudioMixer")
            .apply { isAccessible = true }.invoke(service)
        PlaybackService::class.java.getDeclaredMethod("stopAudioMixer")
            .apply { isAccessible = true }.invoke(service)

        verify(exactly = 1) { mixer.pause() }
        verify(exactly = 1) { mixer.stop() }
    }

    // Override JNI methods so MockK can intercept them without loading LibVLC.
    private class TestMediaPlayer : MediaPlayer(mockk<ILibVLC>()) {
        override fun isPlaying() = true
        override fun isSeekable() = false
        override fun pause() = Unit
        override fun setVolume(volume: Int) = 0
    }
}
