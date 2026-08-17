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
import org.videolan.vlc.viewmodels.SortableModel

class PlaylistsProvider(context: Context, model: SortableModel, val type: Playlist.Type) : MedialibraryProvider<Playlist>(context, model) {

    private val favoritesTitle = context.getString(R.string.favorites)

    private fun favoriteTracksPlaylist() = FavoriteTracksPlaylist(favoritesTitle).takeIf { it.tracksCount > 0 }

    override fun getAll() : Array<Playlist> = mutableListOf<Playlist>().apply {
        favoriteTracksPlaylist()?.let { add(it) }
        addAll(medialibrary.getPlaylists(type, sort, desc, Settings.includeMissing, onlyFavorites))
    }.toTypedArray()

    override fun getPage(loadSize: Int, startposition: Int)  : Array<Playlist> {
        val hasFavoriteTracksPlaylist = model.filterQuery == null && favoriteTracksPlaylist() != null
        val favoriteTracksPlaylist = if (hasFavoriteTracksPlaylist && startposition == 0) favoriteTracksPlaylist() else null
        val list = if (model.filterQuery == null) {
            val offset = if (hasFavoriteTracksPlaylist) (startposition - 1).coerceAtLeast(0) else startposition
            val size = loadSize - if (favoriteTracksPlaylist == null) 0 else 1
            val playlists = if (size > 0) medialibrary.getPagedPlaylists(type, sort, desc, Settings.includeMissing, onlyFavorites, size, offset) else emptyArray()
            mutableListOf<Playlist>().apply {
                favoriteTracksPlaylist?.let { add(it) }
                addAll(playlists)
            }.toTypedArray()
        } else medialibrary.searchPlaylist(model.filterQuery, type, sort, desc, Settings.includeMissing, onlyFavorites, loadSize, startposition)
        model.viewModelScope.launch { completeHeaders(list, startposition) }
        return list
    }

    override fun getTotalCount() = if (model.filterQuery == null) {
        medialibrary.getPlaylists(type, sort, desc, Settings.includeMissing, onlyFavorites).size + if (favoriteTracksPlaylist() == null) 0 else 1
    } else medialibrary.getPlaylistsCount(model.filterQuery)
}
