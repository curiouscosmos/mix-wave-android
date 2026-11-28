package org.videolan.vlc.discourse

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Offline PCM processing; never attaches to playback or an AudioTrack. */
internal object ProcessedClipExporter {
    suspend fun export(context: Context, source: File, range: ClipRange, options: ClipExportOptions,
                       destination: File, progress: (Int) -> Unit): String {
        require(range.startMs >= 0 && range.endMs - range.startMs >= 1_000)
        val coroutine = currentCoroutineContext()
        val clipFrames = (range.endMs - range.startMs) * 48
        ClipAudioDecoder(context, Uri.fromFile(source), range.startMs, range.endMs, coroutine).use { decoder ->
            val speech = ClipResampler(decoder.sampleRate, read = decoder::next)
            // Low-rate MP3 decoders can lose more than one frame to priming; allow at most two.
            val paddingFrames = maxOf(2048, 2 * 1152 * 48_000 / decoder.sampleRate)
            Background(context, options.mixer, coroutine).use { background ->
                val stereo = FloatArray(960)
                val frame = FloatArray(2)
                val music = FloatArray(2)
                val mixed = FloatArray(960)
                var inputPosition = 0L
                var processed = 0L
                var written = 0L
                var missing = 0
                var lastPercent = -1
                ClipAacEncoder(destination, coroutine).use { encoder ->
                    while (written < clipFrames) {
                        coroutine.ensureActive()
                        for (i in 0 until 480) {
                            frame.fill(0f)
                            if (inputPosition < clipFrames) {
                                if (!speech.next(frame)) {
                                    // Allow codec priming/padding, while still rejecting truncated sources.
                                    check(++missing <= paddingFrames) { "Discourse ended before the selected clip" }
                                }
                                inputPosition++
                            }
                            stereo[i * 2] = frame[0]; stereo[i * 2 + 1] = frame[1]
                        }
                        var count = 0
                        for (i in 0 until 480) {
                            val position = processed + i
                            if (position >= clipFrames) continue
                            background.next(music)
                            mixClipFrame(stereo[i * 2], stereo[i * 2 + 1], music, background.gain, mixed, count * 2)
                            val fade = clipFadeGain(position, clipFrames, options.fadeIn, options.fadeOut)
                            mixed[count * 2] *= fade
                            mixed[count * 2 + 1] *= fade
                            count++
                        }
                        if (count > 0) encoder.write(mixed, count)
                        processed += 480
                        written += count
                        val percent = (written * 99 / clipFrames).toInt()
                        if (percent != lastPercent) { progress(percent); lastPercent = percent }
                    }
                    encoder.finish()
                }
            }
        }
        return "audio/mp4"
    }

    private class Background(private val context: Context, private val options: ClipMixerOptions?,
                             private val coroutine: CoroutineContext) : Closeable {
        val gain = clipMixerGain(options?.volume ?: 0)
        private var decoder: ClipAudioDecoder? = null
        private var resampler: ClipResampler? = null
        private var ended = false

        init { if (options != null) open() }

        private fun open() {
            val audio = ClipAudioDecoder(context, Uri.parse(options!!.uri), 0, null, coroutine)
            try {
                resampler = ClipResampler(audio.sampleRate, read = audio::next)
                decoder = audio
            } catch (error: Throwable) { audio.close(); throw error }
        }

        fun next(frame: FloatArray) {
            frame.fill(0f)
            if (options == null || ended) return
            if (resampler!!.next(frame)) return
            close()
            if (!options.loop) { ended = true; return }
            coroutine.ensureActive()
            open()
            check(resampler!!.next(frame)) { "Empty background audio" }
        }

        override fun close() { decoder?.close(); decoder = null; resampler = null }
    }
}
