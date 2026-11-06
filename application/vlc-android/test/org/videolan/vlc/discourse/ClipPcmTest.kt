package org.videolan.vlc.discourse

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ClipPcmTest {
    @Test fun fadesHaveSilentEndpointsAndFullVolumeMiddle() {
        val frames = 6 * 48_000L
        assertEquals(1f, clipFadeGain(0, frames, false, false), 0f)
        assertEquals(1f, clipFadeGain(frames - 1, frames, false, false), 0f)
        assertEquals(0f, clipFadeGain(0, frames, true, false), 0f)
        assertEquals(0.5f, clipFadeGain(48_000, frames, true, false), 0.0001f)
        assertEquals(1f, clipFadeGain(frames - 1, frames, true, false), 0f)
        assertEquals(1f, clipFadeGain(0, frames, false, true), 0f)
        assertEquals(0.5f, clipFadeGain(frames - 48_001, frames, false, true), 0.0001f)
        assertEquals(0f, clipFadeGain(frames - 1, frames, false, true), 0f)
        assertEquals(1f, clipFadeGain(3 * 48_000, frames, true, true), 0f)
        assertEquals(0f, clipFadeGain(0, frames, true, true), 0f)
        assertEquals(0f, clipFadeGain(frames - 1, frames, true, true), 0f)
    }

    @Test fun shortFadesFitWithoutOverlapAndUseClipRelativePositions() {
        for (seconds in 1..4) {
            val frames = seconds * 48_000L
            assertEquals(0f, clipFadeGain(0, frames, true, true), 0f)
            assertEquals(1f, clipFadeGain(frames / 2, frames, true, true), 0f)
            assertEquals(0f, clipFadeGain(frames - 1, frames, true, true), 0f)
            assertEquals(0.5f, clipFadeGain(frames / 4, frames, true, true), 0.0001f)
        }
        val frames = 48_000L
        assertEquals(0.5f, clipFadeGain(frames / 2, frames, true, false), 0.0001f)
        assertEquals(0.5f, clipFadeGain(frames / 2, frames, false, true), 0.0001f)
        assertEquals(1f, clipFadeGain(frames - 1, frames, true, false), 0f)
        assertEquals(1f, clipFadeGain(0, frames, false, true), 0f)
    }

    @Test fun mixerUsesAndroidVolumeAndLimitsBothChannelsTogether() {
        assertEquals(0.5f, clipMixerGain(50))
        val output = FloatArray(2)
        mixClipFrame(0.2f, -0.3f, floatArrayOf(0.4f, 0.2f), clipMixerGain(50), output, 0)
        assertArrayEquals(floatArrayOf(0.4f, -0.2f), output, 0.00001f)
        mixClipFrame(0.8f, 0.4f, floatArrayOf(0.8f, 0.4f), 1f, output, 0)
        assertArrayEquals(floatArrayOf(1f, 0.5f), output, 0.00001f)
        mixClipFrame(-0.8f, -0.4f, floatArrayOf(-0.8f, -0.4f), 1f, output, 0)
        assertArrayEquals(floatArrayOf(-1f, -0.5f), output, 0.00001f)
    }

    @Test fun resamplingPreservesDurationPitchAndSeparateChannels() {
        for (rate in listOf(8_000, 16_000, 44_100, 48_000, 96_000, 192_000)) {
            var sample = 0
            val resampler = ClipResampler(rate) { frame ->
                if (sample >= rate / 4) false else {
                    frame[0] = sin(2 * PI * 440 * sample / rate).toFloat() * 0.4f
                    frame[1] = sin(2 * PI * 880 * sample / rate).toFloat() * 0.2f
                    sample++
                    true
                }
            }
            val frame = FloatArray(2)
            var count = 0
            var squaredError = 0.0
            while (resampler.next(frame)) {
                if (count in 100..11_900) {
                    val left = sin(2 * PI * 440 * count / 48_000) * 0.4
                    val right = sin(2 * PI * 880 * count / 48_000) * 0.2
                    squaredError += (left - frame[0]) * (left - frame[0]) + (right - frame[1]) * (right - frame[1])
                }
                count++
            }
            assertEquals("Duration at $rate Hz", 12_000, count)
            assertTrue("Signal changed at $rate Hz", sqrt(squaredError / 23_600) < 0.003)
        }
    }

    @Test fun downsamplingRejectsFrequenciesAboveOutputNyquist() {
        var sample = 0
        val resampler = ClipResampler(96_000) { frame ->
            if (sample >= 24_000) false else {
                frame[0] = sin(2 * PI * 36_000 * sample / 96_000).toFloat()
                frame[1] = frame[0]
                sample++
                true
            }
        }
        val frame = FloatArray(2)
        var energy = 0.0
        var count = 0
        while (resampler.next(frame)) {
            if (count in 100..11_900) energy += frame[0] * frame[0]
            count++
        }
        assertTrue(sqrt(energy / 11_800) < 0.01)
    }

    @Test fun emptyInputsEndAndSingleSamplesDoNotRepeatForever() {
        assertFalse(ClipResampler(44_100) { false }.next(FloatArray(2)))
        var reads = 0
        val resampler = ClipResampler(44_100) { frame ->
            frame.fill(0.25f)
            reads++ == 0
        }
        val frame = FloatArray(2)
        assertTrue(resampler.next(frame))
        assertTrue(resampler.next(frame))
        assertFalse(resampler.next(frame))
    }
}
