package org.videolan.vlc.bookmarks

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.tools.AppScope
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.discourse.DiscoursePlaybackStore
import org.videolan.vlc.discourse.toMediaWrapper
import org.videolan.vlc.media.MediaUtils

object BookmarkPlayback {
    fun play(context: Context, track: BookmarkedTrack, positionMs: Long): Boolean {
        val position = positionMs.coerceAtLeast(0L)
        val service = PlaybackService.instance
        if (service?.currentMediaWrapper?.bookmarkKey() == track.key) {
            service.setTime(position)
            return true
        }
        val audio = track.audio
        if (audio != null) {
            AppScope.launch {
                val media = withContext(Dispatchers.IO) {
                    DiscoursePlaybackStore(context).register(listOf(audio))
                    audio.toMediaWrapper(context, startAt = position)
                }
                MediaUtils.openMedia(context, media)
            }
            return true
        }
        val uri = track.uri?.toUri() ?: return false
        AppScope.launch {
            val wrapper = withContext(Dispatchers.IO) {
                MLServiceLocator.getAbstractMediaWrapper(uri).apply {
                    setTitle(track.title)
                    track.subtitle?.let { setArtist(it) }
                    track.artwork?.takeIf { it.isNotBlank() }?.let { setArtworkURL(it) }
                    if (track.durationMs > 0L) setLength(track.durationMs)
                    time = position
                }
            }
            MediaUtils.openMedia(context, wrapper)
        }
        return true
    }
}
