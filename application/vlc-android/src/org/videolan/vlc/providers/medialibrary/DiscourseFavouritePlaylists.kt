package org.videolan.vlc.providers.medialibrary

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.resources.AppContextProvider
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseFavouritesStore
import org.videolan.vlc.discourse.toMediaWrapper

const val FAVOURITE_TRACKS_PLAYLIST_ID = Long.MIN_VALUE + 1
const val FAVOURITE_DISCOURSES_PLAYLIST_ID = Long.MIN_VALUE + 2

fun favouriteAudios(context: Context): List<DiscourseAudio> =
    DiscourseFavouritesStore.shared(context).state.value.tracks.map { it.audio }

fun favouriteDiscourses(context: Context): List<Discourse> =
    DiscourseFavouritesStore.shared(context).state.value.discourses.map { it.discourse }

fun isFavouriteTracksPlaylist(item: Any?) = (item as? Playlist)?.id == FAVOURITE_TRACKS_PLAYLIST_ID

fun isFavouriteDiscoursesPlaylist(item: Any?) = (item as? Playlist)?.id == FAVOURITE_DISCOURSES_PLAYLIST_ID

fun isFavouritePlaylist(item: Any?) = isFavouriteTracksPlaylist(item) || isFavouriteDiscoursesPlaylist(item)

private fun durationOf(audio: DiscourseAudio) = ((audio.durationSeconds ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)

/** Read-only virtual playlist backed by [DiscourseFavouritesStore]. */
class FavouriteTracksPlaylist(
    title: String,
    private val context: Context = AppContextProvider.appContext,
    audios: List<DiscourseAudio> = favouriteAudios(context)
) : Playlist(
    FAVOURITE_TRACKS_PLAYLIST_ID,
    title,
    audios.size,
    audios.sumOf(::durationOf),
    0,
    audios.size,
    0,
    0,
    false
) {
    private fun current() = favouriteAudios(context)

    override fun getTracksCount() = current().size
    override fun getDuration() = current().sumOf(::durationOf)
    override fun getTracks(): Array<MediaWrapper> = current().map { it.toMediaWrapper(context) }.toTypedArray()
    override fun getTracks(includeMissing: Boolean, onlyFavorites: Boolean): Array<MediaWrapper> = getTracks()
    override fun getPagedTracks(nbItems: Int, offset: Int, includeMissing: Boolean, onlyFavorites: Boolean): Array<MediaWrapper> =
        current().drop(offset).take(nbItems).map { it.toMediaWrapper(context) }.toTypedArray()
    override fun getRealTracksCount(includeMissing: Boolean, onlyFavorites: Boolean) = current().size
    override fun searchTracks(query: String, sort: Int, desc: Boolean, includeMissing: Boolean, onlyFavorites: Boolean, nbItems: Int, offset: Int): Array<MediaWrapper> =
        current().filter { it.title.contains(query, ignoreCase = true) }
            .drop(offset).take(nbItems).map { it.toMediaWrapper(context) }.toTypedArray()
    override fun searchTracksCount(query: String) = current().count { it.title.contains(query, ignoreCase = true) }
    override fun append(mediaId: Long) = false
    override fun append(mediaIds: LongArray) = false
    override fun append(mediaIds: MutableList<Long>?) = false
    override fun add(mediaId: Long, position: Int) = false
    override fun move(oldPosition: Int, newPosition: Int) = false
    override fun remove(position: Int) = false
    override fun delete() = false
    override fun setName(name: String?) = false
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(title)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<FavouriteTracksPlaylist> {
            override fun createFromParcel(parcel: Parcel) = FavouriteTracksPlaylist(parcel.readString().orEmpty())
            override fun newArray(size: Int) = arrayOfNulls<FavouriteTracksPlaylist>(size)
        }
    }
}

/**
 * Read-only virtual entry for liked discourses. It is a [Playlist] so it can live in the existing
 * playlists provider, but it holds no tracks; the UI opens the discourse list instead.
 */
class FavouriteDiscoursesPlaylist(
    title: String,
    private val context: Context = AppContextProvider.appContext,
    private val count: Int = favouriteDiscourses(context).size
) : Playlist(
    FAVOURITE_DISCOURSES_PLAYLIST_ID,
    title,
    count,
    0,
    0,
    0,
    0,
    0,
    false
) {
    private fun currentCount() = favouriteDiscourses(context).size

    override fun getTracksCount() = currentCount()
    override fun getDuration() = 0L
    override fun getTracks(): Array<MediaWrapper> = emptyArray()
    override fun getTracks(includeMissing: Boolean, onlyFavorites: Boolean): Array<MediaWrapper> = emptyArray()
    override fun getPagedTracks(nbItems: Int, offset: Int, includeMissing: Boolean, onlyFavorites: Boolean): Array<MediaWrapper> = emptyArray()
    override fun getRealTracksCount(includeMissing: Boolean, onlyFavorites: Boolean) = 0
    override fun searchTracks(query: String, sort: Int, desc: Boolean, includeMissing: Boolean, onlyFavorites: Boolean, nbItems: Int, offset: Int): Array<MediaWrapper> = emptyArray()
    override fun searchTracksCount(query: String) = 0
    override fun append(mediaId: Long) = false
    override fun append(mediaIds: LongArray) = false
    override fun append(mediaIds: MutableList<Long>?) = false
    override fun add(mediaId: Long, position: Int) = false
    override fun move(oldPosition: Int, newPosition: Int) = false
    override fun remove(position: Int) = false
    override fun delete() = false
    override fun setName(name: String?) = false
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(title)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<FavouriteDiscoursesPlaylist> {
            override fun createFromParcel(parcel: Parcel) = FavouriteDiscoursesPlaylist(parcel.readString().orEmpty())
            override fun newArray(size: Int) = arrayOfNulls<FavouriteDiscoursesPlaylist>(size)
        }
    }
}
