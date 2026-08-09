/*****************************************************************************
 * AudioMixerProvider.kt
 *****************************************************************************/

package org.videolan.vlc.providers.medialibrary

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.Settings
import org.videolan.vlc.viewmodels.SortableModel

const val KEY_AUDIO_MIXER_FILES = "audio_mixer_files"

class AudioMixerProvider(context: Context, model: SortableModel) : MedialibraryProvider<MediaWrapper>(context, model) {
    override val isAudioPermDependant = true

    override fun canSortByName() = false

    override fun getAll(): Array<MediaWrapper> = getMixerTracks()

    override fun getPage(loadSize: Int, startposition: Int): Array<MediaWrapper> {
        val page = getMixerTracks().drop(startposition).take(loadSize).toTypedArray()
        model.viewModelScope.launch { completeHeaders(page, startposition) }
        return page
    }

    override fun getTotalCount() = getMixerTracks().size

    private fun getMixerTracks(): Array<MediaWrapper> {
        val query = model.filterQuery?.lowercase()
        return Settings.getInstance(context).getString(KEY_AUDIO_MIXER_FILES, null)
            ?.lines()
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.mapNotNull { location ->
                val uri = Uri.parse(location)
                medialibrary.getMedia(uri) ?: MLServiceLocator.getAbstractMediaWrapper(uri)
            }
            ?.filter { query == null || it.title?.lowercase()?.contains(query) == true || it.location.lowercase().contains(query) }
            ?.toList()
            ?.toTypedArray()
            ?: emptyArray()
    }

    companion object {
        fun add(context: Context, media: MediaWrapper) {
            val location = media.uri.toString()
            val settings = Settings.getInstance(context)
            val files = settings.getString(KEY_AUDIO_MIXER_FILES, null)
                ?.lines()
                ?.filter { it.isNotBlank() && it != location }
                ?.toMutableList()
                ?: mutableListOf()
            files.add(location)
            settings.edit { putString(KEY_AUDIO_MIXER_FILES, files.joinToString("\n")) }
        }
    }
}
