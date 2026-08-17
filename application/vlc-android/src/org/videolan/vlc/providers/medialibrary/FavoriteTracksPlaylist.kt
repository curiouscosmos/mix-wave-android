/*****************************************************************************
 * FavoriteTracksPlaylist.kt
 *****************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 *****************************************************************************/

package org.videolan.vlc.providers.medialibrary

import android.os.Parcel
import android.os.Parcelable
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.tools.Settings

const val FAVORITE_TRACKS_PLAYLIST_ID = Long.MIN_VALUE

class FavoriteTracksPlaylist(title: String, private val favoriteTracks: Array<MediaWrapper> = favoriteTracks()) : Playlist(
    FAVORITE_TRACKS_PLAYLIST_ID,
    title,
    favoriteTracks.size,
    favoriteTracks.sumOf { it.length.coerceAtLeast(0L) },
    0,
    favoriteTracks.size,
    0,
    0,
    false
) {
    override fun getTracks(): Array<MediaWrapper> = favoriteTracks()
    override fun getTracks(includeMissing: Boolean, onlyFavorites: Boolean): Array<MediaWrapper> = favoriteTracks(includeMissing = includeMissing)
    override fun getPagedTracks(nbItems: Int, offset: Int, includeMissing: Boolean, onlyFavorites: Boolean): Array<MediaWrapper> =
        Medialibrary.getInstance().getPagedAudio(Medialibrary.SORT_ALPHA, false, includeMissing, true, nbItems, offset)
    override fun getRealTracksCount(includeMissing: Boolean, onlyFavorites: Boolean): Int = favoriteTracks(includeMissing = includeMissing).size
    override fun searchTracks(query: String, sort: Int, desc: Boolean, includeMissing: Boolean, onlyFavorites: Boolean, nbItems: Int, offset: Int): Array<MediaWrapper> =
        favoriteTracks(sort, desc, includeMissing).filter { it.title.contains(query, ignoreCase = true) }.drop(offset).take(nbItems).toTypedArray()
    override fun searchTracksCount(query: String): Int = favoriteTracks().count { it.title.contains(query, ignoreCase = true) }
    override fun append(mediaId: Long) = false
    override fun append(mediaIds: LongArray) = false
    override fun append(mediaIds: MutableList<Long>?) = false
    override fun add(mediaId: Long, position: Int) = false
    override fun move(oldPosition: Int, newPosition: Int) = false
    override fun remove(position: Int) = false
    override fun delete() = false
    override fun setName(name: String?) = false
    override fun setFavorite(favorite: Boolean) = false
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(title)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<FavoriteTracksPlaylist> {
            override fun createFromParcel(parcel: Parcel) = FavoriteTracksPlaylist(parcel.readString().orEmpty())
            override fun newArray(size: Int) = arrayOfNulls<FavoriteTracksPlaylist>(size)
        }
    }
}

fun isFavoriteTracksPlaylist(playlist: Any?) = (playlist as? Playlist)?.id == FAVORITE_TRACKS_PLAYLIST_ID

private fun favoriteTracks(sort: Int = Medialibrary.SORT_ALPHA, desc: Boolean = false, includeMissing: Boolean = Settings.includeMissing): Array<MediaWrapper> =
    Medialibrary.getInstance().getAudio(sort, desc, includeMissing, true)
