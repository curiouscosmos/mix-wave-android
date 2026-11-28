package org.videolan.vlc.bookmarks

import android.content.Context
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.discourseAudioSnapshot
import org.videolan.vlc.discourse.resolveDiscourseUrl
import org.videolan.vlc.media.MediaUtils

enum class BookmarkTrackKind { DISCOURSE, LOCAL }

data class BookmarkStamp(val positionMs: Long, val createdAt: Long)

data class BookmarkedTrack(
    val key: String,
    val kind: BookmarkTrackKind,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val durationMs: Long,
    val audio: DiscourseAudio? = null,
    val uri: String? = null,
    val updatedAt: Long = 0L,
    val stamps: List<BookmarkStamp> = emptyList()
)

fun MediaWrapper.bookmarkKey(): String? {
    val audio = discourseAudioSnapshot()
    return if (audio != null) "discourse:${audio.id}"
    else uri?.toString()?.takeIf { it.isNotBlank() }?.let { "local:$it" }
}

fun MediaWrapper.toBookmarkedTrack(context: Context): BookmarkedTrack? {
    val key = bookmarkKey() ?: return null
    val audio = discourseAudioSnapshot()
    val trackTitle = audio?.title?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment.orEmpty()
    val trackSubtitle = if (audio != null) {
        audio.discourseName.takeIf { it.isNotBlank() }
    } else {
        MediaUtils.getDisplaySubtitle(context, this)
            ?: albumName?.takeIf { it.isNotBlank() }
            ?: artistName?.takeIf { it.isNotBlank() }
    }
    val duration = audio?.durationSeconds
        ?.takeIf { it.isFinite() && it > 0 }
        ?.times(1_000)
        ?.toLong()
        ?: length.takeIf { it > 0 }
        ?: 0L
    return BookmarkedTrack(
        key = key,
        kind = if (audio != null) BookmarkTrackKind.DISCOURSE else BookmarkTrackKind.LOCAL,
        title = trackTitle,
        subtitle = trackSubtitle,
        artwork = if (audio != null) resolveDiscourseUrl(audio.discourseThumbnailUrl) else artworkMrl,
        durationMs = duration,
        audio = audio,
        uri = if (audio == null) uri.toString() else null
    )
}
