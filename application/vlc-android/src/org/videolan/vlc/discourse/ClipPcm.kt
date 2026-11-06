package org.videolan.vlc.discourse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Android's LibVLC AudioTrack output uses linear gain for mixer volumes from 0 to 100.
internal fun clipMixerGain(volume: Int) = volume.coerceIn(0, 100) / 100f

/** Clip-relative envelope at the exporter's 48 kHz rate, including silent endpoints. */
internal fun clipFadeGain(position: Long, totalFrames: Long, fadeIn: Boolean, fadeOut: Boolean): Float {
    if (!fadeIn && !fadeOut) return 1f
    val fadeFrames = minOf(96_000L, if (fadeIn && fadeOut) totalFrames / 2 else totalFrames)
    val span = (fadeFrames - 1).coerceAtLeast(1).toFloat()
    val start = if (fadeIn) (position / span).coerceIn(0f, 1f) else 1f
    val end = if (fadeOut) ((totalFrames - 1 - position) / span).coerceIn(0f, 1f) else 1f
    return minOf(start, end)
}

internal fun mixClipFrame(left: Float, right: Float, music: FloatArray, gain: Float, output: FloatArray, offset: Int) {
    val l = left + music[0] * gain
    val r = right + music[1] * gain
    val peak = maxOf(1f, abs(l), abs(r))
    output[offset] = l / peak
    output[offset + 1] = r / peak
}

/** Pull-based, windowed-sinc conversion. History and fractional phase survive decoder buffers. */
internal class ClipResampler(private val inputRate: Int, private val outputRate: Int = 48_000,
                             private val read: (FloatArray) -> Boolean) {
    init { require(inputRate in 8_000..192_000 && outputRate in 8_000..192_000) }
    private val history = FloatArray(64 * 2)
    private val frame = FloatArray(2)
    private var loaded = 0L
    private var ended = false
    private var outputFrame = 0L
    private val coefficients = if (inputRate == outputRate) emptyArray() else Array(256) { phase ->
        val cutoff = minOf(1.0, outputRate.toDouble() / inputRate) * 0.95
        DoubleArray(32) { tap ->
            val distance = tap - 15 - phase / 256.0
            val sinc = if (abs(distance) < 1e-9) cutoff else sin(PI * cutoff * distance) / (PI * distance)
            sinc * (0.5 + 0.5 * cos(PI * distance / 16))
        }.let { values -> val sum = values.sum(); FloatArray(32) { (values[it] / sum).toFloat() } }
    }

    fun next(output: FloatArray): Boolean {
        if (inputRate == outputRate) return read(output)
        val position = outputFrame * inputRate
        val center = position / outputRate
        while (!ended && loaded <= center + 16) {
            if (!read(frame)) ended = true
            else {
                val index = (loaded % 64).toInt() * 2
                history[index] = frame[0]; history[index + 1] = frame[1]
                loaded++
            }
        }
        if (ended && position >= loaded * outputRate) return false
        val weights = coefficients[((position % outputRate) * 256 / outputRate).toInt()]
        var left = 0f
        var right = 0f
        for (tap in 0..31) {
            val sample = center + tap - 15
            if (sample >= 0 && sample < loaded) {
                val index = (sample % 64).toInt() * 2
                left += history[index] * weights[tap]
                right += history[index + 1] * weights[tap]
            }
        }
        output[0] = left; output[1] = right
        outputFrame++
        return true
    }
}
