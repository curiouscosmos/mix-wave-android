package org.videolan.vlc.discourse

import android.net.Uri

/** Parsed https://rajneeshdiscourse.com/discourses/... link. */
data class DiscourseDeepLink(val discourseId: String, val audioId: String?) {
    companion object {
        private const val HOST = "rajneeshdiscourse.com"

        fun parse(uri: Uri?): DiscourseDeepLink? {
            if (uri == null) return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            val host = uri.host?.lowercase() ?: return null
            if (host != HOST && host != "www.$HOST") return null
            val segments = uri.pathSegments
            if (segments.size < 2 || segments[0] != "discourses") return null
            val discourseId = segments[1].trim().takeIf(String::isNotEmpty) ?: return null
            val audioId = when {
                segments.size == 2 -> null
                segments.size == 4 && segments[2] == "tracks" ->
                    segments[3].trim().takeIf(String::isNotEmpty) ?: return null
                else -> return null
            }
            return DiscourseDeepLink(discourseId, audioId)
        }
    }
}
