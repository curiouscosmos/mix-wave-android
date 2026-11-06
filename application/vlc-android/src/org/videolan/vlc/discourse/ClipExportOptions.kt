package org.videolan.vlc.discourse

import org.json.JSONObject
import org.videolan.vlc.audio.NoiseCancellationLevel

internal data class ClipMixerOptions(val uri: String, val title: String, val volume: Int, val loop: Boolean) {
    init { require(uri.isNotBlank() && volume in 0..100) }
}

internal enum class ClipOutput { AUDIO, VIDEO }

/** Export snapshot; independent of the service's subsequent playback settings. */
internal data class ClipExportOptions(
    val noiseLevel: NoiseCancellationLevel = NoiseCancellationLevel.OFF,
    val mixer: ClipMixerOptions? = null,
    val output: ClipOutput = ClipOutput.AUDIO,
    val visualUri: String? = null,
    val captionPath: String? = null,
    val fadeIn: Boolean = false,
    val fadeOut: Boolean = false
) {
    val processed get() = noiseLevel != NoiseCancellationLevel.OFF || mixer != null || fadeIn || fadeOut

    fun toJson() = JSONObject().apply {
        put("noise", noiseLevel.name)
        put("output", output.name)
        put("fade_in", fadeIn)
        put("fade_out", fadeOut)
        visualUri?.let { put("visual_uri", it) }
        captionPath?.let { put("caption_path", it) }
        mixer?.let {
            put("mixer", JSONObject().apply {
                put("uri", it.uri); put("title", it.title); put("volume", it.volume); put("loop", it.loop)
            })
        }
    }

    companion object {
        fun fromJson(json: JSONObject?) = if (json == null) ClipExportOptions() else ClipExportOptions(
            NoiseCancellationLevel.valueOf(json.getString("noise")),
            json.optJSONObject("mixer")?.let { ClipMixerOptions(it.getString("uri"), it.getString("title"), it.getInt("volume"), it.getBoolean("loop")) },
            ClipOutput.valueOf(json.optString("output", "AUDIO")),
            if (json.has("visual_uri")) json.getString("visual_uri") else null,
            if (json.has("caption_path")) json.getString("caption_path") else null,
            json.optBoolean("fade_in", false),
            json.optBoolean("fade_out", false)
        )
    }
}
