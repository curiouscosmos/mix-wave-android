package org.videolan.vlc.discourse

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

/** Each explicit timing is one selectable segment; no word timing is inferred. */
internal fun parseCaptions(source: String): List<CaptionCue> {
    val timing = Regex("^(\\S+)\\s+-->\\s+(\\S+).*$")
    fun timestamp(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().split('.')
        if (seconds.size != 2 || seconds[1].length != 3) return null
        val s = seconds[0].toLongOrNull() ?: return null
        val ms = seconds[1].toLongOrNull() ?: return null
        val m = parts[parts.size - 2].toLongOrNull() ?: return null
        val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        if (s !in 0..59 || m !in 0..59 || h !in 0..9999 || ms !in 0..999) return null
        return ((h * 60 + m) * 60 + s) * 1000 + ms
    }
    return source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        .split(Regex("\n[ \\t]*\n")).mapNotNull { block ->
            val lines = block.lines()
            if (lines.firstOrNull()?.let { it.startsWith("NOTE") || it == "STYLE" || it == "REGION" } == true) return@mapNotNull null
            val index = lines.indexOfFirst { timing.matches(it) }
            if (index < 0) return@mapNotNull null
            val match = timing.matchEntire(lines[index]) ?: return@mapNotNull null
            val start = timestamp(match.groupValues[1]) ?: return@mapNotNull null
            val end = timestamp(match.groupValues[2]) ?: return@mapNotNull null
            val text = lines.drop(index + 1).joinToString("\n").trim()
            if (end <= start || text.isEmpty()) null else CaptionCue(start, end, text)
        }.sortedBy { it.startMs }
}

internal fun captionUrl(path: String): HttpUrl? {
    val value = path.trim()
    val resolved = if (value.startsWith("https://", true)) value else resolveDiscourseUrl(value)
    return resolved?.toHttpUrlOrNull()?.takeIf { it.isHttps }
}

internal fun captionRange(range: ClipRange, cue: CaptionCue, start: Boolean, duration: Long): ClipRange? =
    (if (start) range.copy(startMs = cue.startMs) else range.copy(endMs = cue.endMs)).takeIf { it.isValid(duration) }

internal fun activeCaption(cues: List<CaptionCue>, time: Long): Int {
    var low = 0
    var high = cues.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (cues[middle].startMs <= time) low = middle + 1 else high = middle
    }
    return (low - 1).takeIf { it >= 0 && time < cues[it].endMs } ?: -1
}

/** A separate client deliberately has no API credentials or connectivity interceptor. */
internal fun captionClient(directory: File): OkHttpClient = OkHttpClient.Builder()
    .cache(Cache(directory, 10L * 1024 * 1024))
    .addInterceptor(StaleCacheInterceptor())
    .addNetworkInterceptor { chain ->
        chain.proceed(chain.request()).newBuilder().header("Cache-Control", "public, max-age=86400").build()
    }
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

internal class DiscourseCaptions(context: Context) {
    private val client = synchronized(DiscourseCaptions::class.java) {
        sharedClient ?: captionClient(File(context.applicationContext.cacheDir, "discourse-captions")).also { sharedClient = it }
    }

    suspend fun load(path: String): List<CaptionCue> {
        val url = captionUrl(path) ?: throw IOException("Invalid caption URL")
        val call = client.newCall(Request.Builder().url(url).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val cues = response.use {
                            if (!it.isSuccessful) throw IOException("Caption HTTP ${it.code}")
                            val source = it.body?.source() ?: throw IOException("Empty captions")
                            if (source.request(10L * 1024 * 1024 + 1)) throw IOException("Captions exceed 10 MB")
                            parseCaptions(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(cues)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    companion object { private var sharedClient: OkHttpClient? = null }
}
