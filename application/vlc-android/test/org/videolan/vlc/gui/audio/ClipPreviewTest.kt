package org.videolan.vlc.gui.audio

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.discourse.ClipRange

class ClipPreviewTest {
    @Before fun stubAndroidLogging() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
    }

    @After fun restoreAndroidLogging() { unmockkStatic(Log::class) }

    @Test fun pausesAtOrBeyondEndEvenWhenUiClockHasNotCaughtUp() {
        listOf(5_000L, 5_080L).forEach { time ->
            val service = mockk<PlaybackService>(relaxed = true)
            every { service.isPlaying } returns true
            every { service.getTime() } returns 4_000L

            pauseClipPreviewAtEnd(service, ClipRange(1_000, 5_000), time)

            verify(exactly = 1) { service.pause() }
            verify(exactly = 1) { service.seek(5_000, any(), true, any()) }
        }
    }

    @Test fun leavesPlaybackAloneBeforeEndOrWhilePaused() {
        listOf(true to 4_999L, false to 5_000L).forEach { (playing, time) ->
            val service = mockk<PlaybackService>(relaxed = true)
            every { service.isPlaying } returns playing

            pauseClipPreviewAtEnd(service, ClipRange(1_000, 5_000), time)

            verify(exactly = 0) { service.pause() }
            verify(exactly = 0) { service.seek(any(), any(), any(), any()) }
        }
    }
}
