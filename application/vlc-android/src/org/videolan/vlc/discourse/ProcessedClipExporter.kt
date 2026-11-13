package org.videolan.vlc.discourse

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.videolan.vlc.audio.NoiseCancellationLevel
import org.videolan.vlc.audio.createNoiseFilter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/** Offline PCM processing; never attaches to playback or an AudioTrack. */
internal object ProcessedClipExporter {
    suspend fun export(context: Context, source: File, range: ClipRange, options: ClipExportOptions,
                       destination: File, progress: (Int) -> Unit): String {
        require(range.startMs >= 0 && range.endMs - range.startMs >= 1_000)
        val coroutine = currentCoroutineContext()
        val denoise = options.noiseLevel != NoiseCancellationLevel.OFF
        val warmStart = if (denoise) (range.startMs - 1_000).coerceAtLeast(0) else range.startMs
        val warmFrames = (range.startMs - warmStart) * 48
        val clipFrames = (range.endMs - range.startMs) * 48
        val inputFrames = warmFrames + clipFrames
        ClipAudioDecoder(context, Uri.fromFile(source), warmStart, range.endMs, coroutine).use { decoder ->
            val speech = ClipResampler(decoder.sampleRate, read = decoder::next)
            // Low-rate MP3 decoders can lose more than one frame to priming; allow at most two.
            val paddingFrames = maxOf(2048, 2 * 1152 * 48_000 / decoder.sampleRate)
            Background(context, options.mixer, coroutine).use { background ->
                val filter = if (denoise) createNoiseFilter(context, options.noiseLevel) else null
                try {
                    // Bundled mobile model: 480 samples per hop and one hop of STFT latency.
                    check(filter == null || filter.frameLength == 960L) { "Unsupported noise model frame size" }
                    val noise = ByteBuffer.allocateDirect(960).order(ByteOrder.nativeOrder())
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
                            noise.clear()
                            for (i in 0 until 480) {
                                frame.fill(0f)
                                if (inputPosition < inputFrames) {
                                    if (!speech.next(frame)) {
                                        // Allow codec priming/padding, while still rejecting truncated sources.
                                        check(++missing <= paddingFrames) { "Discourse ended before the selected clip" }
                                    }
                                    inputPosition++
                                }
                                stereo[i * 2] = frame[0]; stereo[i * 2 + 1] = frame[1]
                                if (denoise) noise.putShort(((frame[0] + frame[1]) * 0.5f).coerceIn(-1f, 1f).let { (it * 32767).toInt().toShort() })
                            }
                            if (filter != null) {
                                noise.flip()
                                check(filter.processFrame(noise).isFinite()) { "Noise processing failed" }
                            }
                            var count = 0
                            for (i in 0 until 480) {
                                val position = processed + i - (if (denoise) 480 else 0) - warmFrames
                                if (position < 0 || position >= clipFrames) continue
                                val left = if (denoise) noise.getShort(i * 2) / 32768f else stereo[i * 2]
                                val right = if (denoise) left else stereo[i * 2 + 1]
                                background.next(music)
                                mixClipFrame(left, right, music, background.gain, mixed, count * 2)
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
                } finally { filter?.release() }
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
