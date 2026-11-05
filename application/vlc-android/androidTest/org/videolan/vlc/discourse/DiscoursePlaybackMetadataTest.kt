package org.videolan.vlc.discourse

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.resources.AppContextProvider
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import org.videolan.vlc.media.PlayerController

@RunWith(AndroidJUnit4::class)
class DiscoursePlaybackMetadataTest {
    @Test
    fun restoredDownloadedTrackUsesTagMetadataInsteadOfFileName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppContextProvider.init(context)
        val audio = DiscourseAudio(
            id = "audio-id",
            discourseId = "discourse-id",
            discourseName = "Es Dhammo Sanantano",
            discourseThumbnailUrl = "thumb.jpg",
            language = "english",
            title = "The Real Track Title",
            audioUrl = "track.mp3",
            durationSeconds = 60.0,
            fileSize = 1_000L,
            mimeType = "audio/mpeg",
            trackNumber = 4,
            createdAt = "",
            updatedAt = "",
            hasCc = true,
            ccPath = "/Hindi captions.srt"
        )
        val json = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(DiscourseAudio::class.java).toJson(audio)
        val media = MLServiceLocator.getAbstractMediaWrapper(
            Uri.parse("file:///data/user/0/com.oshodiscourse.debug/files/audio-id.audio")
        ).apply {
            tag = "osho_discourse:discourse-id|audio-id|$json"
        }

        media.restoreDiscourseMetadata()

        assertEquals("The Real Track Title", media.title)
        assertEquals("Osho", media.artistName)
        assertEquals(60_000L, media.length)
        assertEquals(audio, media.discourseAudioSnapshot())

        // Native metadata refreshes must not copy the restored wrapper's UUID into its display title.
        val vlc = LibVLC(context, arrayListOf("--no-video"))
        val player = MediaPlayer(vlc)
        try {
            repeat(3) {
                media.updateMeta(player)
                assertEquals("The Real Track Title", media.title)
            }
        } finally {
            player.release()
            vlc.release()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val controller = PlayerController(context)
            try {
                repeat(3) {
                    media.setTitle("audio-id.audio")
                    media.setDisplayTitle("audio-id.audio")
                    controller.updateCurrentMeta(-1, media)
                    assertEquals("The Real Track Title", media.title)
                    assertEquals("Osho", media.artistName)
                }
            } finally {
                controller.release()
            }
        }
        media.updateCaptionSnapshot(audio.copy(title = "Different title", ccPath = "/refreshed.vtt"))
        assertEquals(audio.copy(ccPath = "/refreshed.vtt"), media.discourseAudioSnapshot())
    }
}
