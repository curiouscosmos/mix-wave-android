package org.videolan.vlc.discourse

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.videolan.tools.Settings

internal object ClipExportFolder {
    private const val URI_KEY = "clip_export_folder_uri"
    private const val NAME_KEY = "clip_export_folder_name"

    fun uri(context: Context): String? = Settings.getInstance(context).getString(URI_KEY, null)
    fun name(context: Context): String? = Settings.getInstance(context).getString(NAME_KEY, null)

    fun save(context: Context, uri: String?, name: String?) {
        check(Settings.getInstance(context).edit().putString(URI_KEY, uri).putString(NAME_KEY, name).commit())
    }

    fun directory(context: Context, uri: String): DocumentFile {
        try {
            val treeUri = Uri.parse(uri)
            check(context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission })
            return DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory && it.canWrite() }
                ?: throw ClipExportFolderUnavailable()
        } catch (error: ClipExportFolderUnavailable) {
            throw error
        } catch (_: Exception) {
            throw ClipExportFolderUnavailable()
        }
    }
}

internal class ClipExportFolderUnavailable : IllegalStateException("Export folder is unavailable")
