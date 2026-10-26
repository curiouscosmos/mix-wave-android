/*****************************************************************************
 * PlaylistsProvider.kt
 *****************************************************************************
 * Copyright © 2019 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.providers.medialibrary

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.discourse.DiscourseFavouritesStore
import org.videolan.vlc.viewmodels.SortableModel

class PlaylistsProvider(context: Context, model: SortableModel, val type: Playlist.Type) : MedialibraryProvider<Playlist>(context, model) {

    private val favoritesTitle = context.getString(R.string.favorites)

    private fun favoriteTracksPlaylist() = FavoriteTracksPlaylist(favoritesTitle).takeIf { it.tracksCount > 0 }

    private fun virtualPlaylists(): List<Playlist> {
        val favourites = DiscourseFavouritesStore.shared(context).state.value
        return buildList {
            if (favourites.tracks.isNotEmpty())
                add(FavouriteTracksPlaylist(context.getString(R.string.favourite_tracks), context))
            if (favourites.discourses.isNotEmpty())
                add(FavouriteDiscoursesPlaylist(context.getString(R.string.favourite_discourses), context))
            favoriteTracksPlaylist()?.let { add(it) }
        }
    }

    override fun getAll() : Array<Playlist> =
        (virtualPlaylists() + medialibrary.getPlaylists(type, sort, desc, Settings.includeMissing, onlyFavorites)).toTypedArray()

    override fun getPage(loadSize: Int, startposition: Int)  : Array<Playlist> {
        val virtual = if (model.filterQuery == null) virtualPlaylists() else emptyList()
        val page = virtual.drop(startposition).take(loadSize).toMutableList()
        val mediaOffset = (startposition - virtual.size).coerceAtLeast(0)
        val remaining = loadSize - page.size
        if (remaining > 0) {
            val playlists = if (model.filterQuery == null)
                medialibrary.getPagedPlaylists(type, sort, desc, Settings.includeMissing, onlyFavorites, remaining, mediaOffset)
            else
                medialibrary.searchPlaylist(model.filterQuery, type, sort, desc, Settings.includeMissing, onlyFavorites, remaining, mediaOffset)
            page.addAll(playlists)
        }
        model.viewModelScope.launch { completeHeaders(page.toTypedArray(), startposition) }
        return page.toTypedArray()
    }

    override fun getTotalCount() = if (model.filterQuery == null) {
        virtualPlaylists().size + medialibrary.getPlaylists(type, sort, desc, Settings.includeMissing, onlyFavorites).size
    } else medialibrary.getPlaylistsCount(model.filterQuery)
}
