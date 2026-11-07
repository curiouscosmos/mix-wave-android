package org.videolan.vlc.discourse

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClipExportOptionsTest {
    @Test fun videoRatioSurvivesExportRequests() {
        for (ratio in ClipVideoRatio.values()) {
            val options = ClipExportOptions(output = ClipOutput.VIDEO, visualUri = "content://visual", videoRatio = ratio)
            assertEquals(options, ClipExportOptions.fromJson(JSONObject(options.toJson().toString())))
        }
        assertEquals(ClipVideoRatio.ORIGINAL, ClipExportOptions.fromJson(null).videoRatio)
        assertEquals(ClipVideoRatio.ORIGINAL, ClipExportOptions.fromJson(JSONObject("{\"noise\":\"OFF\"}")).videoRatio)
    }

    @Test fun folderSurvivesPersistedAudioAndVideoRequests() {
        for (output in ClipOutput.values()) {
            val options = ClipExportOptions(output = output, fadeIn = true, fadeOut = true,
                exportFolderUri = "content://com.android.externalstorage.documents/tree/primary%3AMy%20Clips")
            val request = options.toJson().toString()
            assertEquals(options, ClipExportOptions.fromJson(JSONObject(request)))
            // Changing a later preference must not change the request's destination.
            assertNotEquals(options.copy(exportFolderUri = null), ClipExportOptions.fromJson(JSONObject(request)))
        }
    }

    @Test fun defaultAndOldRequestsUseDownloads() {
        assertNull(ClipExportOptions.fromJson(null).exportFolderUri)
        assertNull(ClipExportOptions.fromJson(JSONObject("{\"noise\":\"OFF\"}")).exportFolderUri)
        val defaults = ClipExportOptions()
        assertEquals(defaults, ClipExportOptions.fromJson(JSONObject(defaults.toJson().toString())))
    }

    @Test fun choosingFolderDoesNotEnableAudioProcessing() {
        assertFalse(ClipExportOptions(exportFolderUri = "content://documents/tree/clips").processed)
    }
}
