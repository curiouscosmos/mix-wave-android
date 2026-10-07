package org.videolan.vlc.discourse

import android.content.Context
import android.net.Uri
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.media.MediaUtils
import org.videolan.tools.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

private const val DISCOURSE_TAG_PREFIX = "osho_discourse:"
private const val DISCOURSE_TAG_SEPARATOR = "|"
private val playbackAudioAdapter by lazy {
    Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(DiscourseAudio::class.java)
}

data class DiscoursePlaybackIds(val discourseId: String, val audioId: String)

internal fun discourseContinuation(audios: List<DiscourseAudio>, ids: DiscoursePlaybackIds): List<DiscourseAudio> {
    val tracks = audios.filter { it.discourseId == ids.discourseId }.distinctBy { it.id }
    val selected = tracks.indexOfFirst { it.id == ids.audioId }
    return if (selected < 0) emptyList() else tracks.drop(selected + 1)
}

internal fun discourseCheckpointDue(lastCheckpoint: Long, elapsedTime: Long) = elapsedTime - lastCheckpoint >= 30_000L

fun DiscourseAudio.toMediaWrapper(context: Context): MediaWrapper = MLServiceLocator.getAbstractMediaWrapper(
    DiscourseDownloadStore(context).playbackUri(this) ?: Uri.EMPTY,
    0L,
    0f,
    durationSeconds?.times(1_000)?.toLong() ?: 0L,
    MediaWrapper.TYPE_AUDIO,
    null,
    title,
    0L,
    0L,
    "Osho",
    "Discourse",
    0L,
    discourseName,
    "Osho",
    0,
    0,
    resolveDiscourseUrl(discourseThumbnailUrl),
    -2,
    -2,
    trackNumber ?: 0,
    0,
    0L,
    0L,
    0L
).apply {
    time = DiscoursePlaybackStore(context).position(this@toMediaWrapper.id) ?: 0L
    tag = "$DISCOURSE_TAG_PREFIX${this@toMediaWrapper.discourseId}$DISCOURSE_TAG_SEPARATOR${this@toMediaWrapper.id}$DISCOURSE_TAG_SEPARATOR${playbackAudioAdapter.toJson(this@toMediaWrapper)}"
}

fun MediaWrapper.discoursePlaybackIds(): DiscoursePlaybackIds? {
    val value = tag?.removePrefix(DISCOURSE_TAG_PREFIX) ?: return null
    if (value == tag) return null
    val ids = value.split(DISCOURSE_TAG_SEPARATOR, limit = 3)
    return if (ids.size >= 2 && ids.take(2).all(String::isNotBlank)) DiscoursePlaybackIds(ids[0], ids[1]) else null
}

internal fun MediaWrapper.recordDiscourseRecentlyPlayed(context: Context) {
    val ids = discoursePlaybackIds() ?: return
    val json = tag?.split(DISCOURSE_TAG_SEPARATOR, limit = 3)?.getOrNull(2) ?: return
    val audio = runCatching { playbackAudioAdapter.fromJson(json) }.getOrNull() ?: return
    if (audio.id == ids.audioId && audio.discourseId == ids.discourseId) {
        DiscourseRepository(context).recordRecentlyPlayed(audio)
    }
}

fun Context.playDiscourseAudio(audio: DiscourseAudio) {
    val context = applicationContext
    AppScope.launch {
        val media = withContext(Dispatchers.IO) {
            DiscoursePlaybackStore(context).register(listOf(audio))
            audio.toMediaWrapper(context)
        }
        MediaUtils.openMedia(context, media)
    }
}

fun Context.playDiscourseAudios(audios: List<DiscourseAudio>, position: Int = 0) {
    val context = applicationContext
    AppScope.launch {
        val media = withContext(Dispatchers.IO) {
            DiscoursePlaybackStore(context).register(audios)
            audios.map { it.toMediaWrapper(context) }
        }
        MediaUtils.openList(context, media, position)
    }
}
