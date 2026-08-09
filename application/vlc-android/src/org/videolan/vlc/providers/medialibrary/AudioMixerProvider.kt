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
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.Settings
import org.videolan.vlc.viewmodels.SortableModel

const val KEY_AUDIO_MIXER_FILES = "audio_mixer_files"
const val KEY_AUDIO_MIXER_ENABLED = "audio_mixer_enabled"
const val KEY_AUDIO_MIXER_LOOP = "audio_mixer_loop"
const val KEY_AUDIO_MIXER_SELECTED = "audio_mixer_selected"
const val KEY_AUDIO_MIXER_VOLUME = "audio_mixer_volume"

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
        private fun locations(context: Context) = Settings.getInstance(context).getString(KEY_AUDIO_MIXER_FILES, null)
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toMutableList()
            ?: mutableListOf()

        private fun saveLocations(context: Context, files: List<String>) {
            Settings.getInstance(context).edit { putString(KEY_AUDIO_MIXER_FILES, files.joinToString("\n")) }
        }

        fun count(context: Context) = locations(context).size

        fun snapshot(context: Context) = locations(context).toList()

        fun replace(context: Context, files: List<String>) = saveLocations(context, files.distinct())

        fun selected(context: Context): MediaWrapper? {
            val location = Settings.getInstance(context).getString(KEY_AUDIO_MIXER_SELECTED, null) ?: return null
            val uri = Uri.parse(location)
            return Medialibrary.getInstance().getMedia(uri) ?: MLServiceLocator.getAbstractMediaWrapper(uri)
        }

        fun select(context: Context, media: MediaWrapper) {
            Settings.getInstance(context).edit { putString(KEY_AUDIO_MIXER_SELECTED, media.uri.toString()) }
        }

        fun remove(context: Context, media: MediaWrapper) {
            val location = media.uri.toString()
            saveLocations(context, locations(context).filter { it != location })
            val settings = Settings.getInstance(context)
            if (settings.getString(KEY_AUDIO_MIXER_SELECTED, null) == location) {
                settings.edit {
                    remove(KEY_AUDIO_MIXER_SELECTED)
                    putBoolean(KEY_AUDIO_MIXER_ENABLED, false)
                }
            }
        }

        fun clear(context: Context) {
            Settings.getInstance(context).edit {
                remove(KEY_AUDIO_MIXER_FILES)
                remove(KEY_AUDIO_MIXER_SELECTED)
                putBoolean(KEY_AUDIO_MIXER_ENABLED, false)
            }
        }

        fun move(context: Context, oldPosition: Int, newPosition: Int) {
            val files = locations(context)
            if (oldPosition !in files.indices || newPosition !in files.indices) return
            files.add(newPosition, files.removeAt(oldPosition))
            saveLocations(context, files)
        }

        fun add(context: Context, media: MediaWrapper) {
            val location = media.uri.toString()
            val files = locations(context).filter { it != location }.toMutableList()
            files.add(location)
            saveLocations(context, files)
        }
    }
}
