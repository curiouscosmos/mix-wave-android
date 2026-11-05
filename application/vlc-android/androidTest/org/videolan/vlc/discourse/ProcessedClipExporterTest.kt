package org.videolan.vlc.discourse

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.vlc.audio.NoiseCancellationLevel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class ProcessedClipExporterTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun lowSampleRateMp3ExportsThroughTheSelectedEnd() = runBlocking {
        val source = File(context.cacheDir, "speech-24k.mp3")
        val output = File(context.cacheDir, "speech-24k.m4a")
        try {
            instrumentation.context.assets.open("clips/speech-24k.mp3").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            for (range in listOf(ClipRange(0, 2_000), ClipRange(2_000, 4_000), ClipRange(4_000, 6_000))) {
                var progress = 0
                try {
                    ProcessedClipExporter.export(context, source, range, ClipExportOptions(), output) { progress = it }
                } catch (error: Exception) { throw AssertionError("Export failed at $progress% for $range", error) }
                checkContainer(output, 2_000)
            }
            try {
                ProcessedClipExporter.export(context, source, ClipRange(5_000, 7_000), ClipExportOptions(), output) {}
                fail("Truncated audio was silently padded")
            } catch (error: IllegalStateException) {
                assertEquals("Discourse ended before the selected clip", error.message)
            }
        } finally { source.delete(); output.delete() }
    }

    @Test fun mixerStartsAtZeroAndHonorsVolumeLoopAndStereo() = runBlocking {
        val source = wav("silent", 48_000, 1, 3_000) { _, _ -> 0f }
        val music = wav("music", 44_100, 2, 600) { sample, channel ->
            val frequency = (if (sample < 13_230) 440 else 880) * (if (channel == 0) 1.0 else 1.5)
            (0.4 * sin(2 * PI * frequency * sample / 44_100)).toFloat()
        }
        val once = File(context.cacheDir, "once.m4a")
        val loop = File(context.cacheDir, "loop.m4a")
        try {
            val range = ClipRange(1_000, 2_500)
            for ((file, volume, repeat) in listOf(Triple(once, 25, false), Triple(loop, 50, true))) {
                val options = ClipExportOptions(mixer = ClipMixerOptions(Uri.fromFile(music).toString(), "Music", volume, repeat))
                assertEquals("audio/mp4", ProcessedClipExporter.export(context, source, range, options, file) {})
                checkContainer(file, 1_500)
            }
            val single = decode(once)
            val repeated = decode(loop)
            assertTrue(tone(repeated, 100, 200, 0, 440.0) > 0.10)
            assertTrue(tone(repeated, 100, 200, 0, 880.0) < 0.01)
            assertTrue(tone(repeated, 100, 200, 1, 660.0) > 0.10)
            assertTrue(tone(repeated, 700, 800, 0, 440.0) > 0.10)
            assertTrue(tone(repeated, 1_300, 1_400, 0, 440.0) > 0.10)
            val ratio = rms(repeated, 100, 200) / rms(single, 100, 200)
            assertTrue("Volume ratio $ratio", ratio in 1.85..2.15)
            assertTrue("Non-looped background did not stop", rms(single, 1_100, 1_400) < 0.003)
        } finally { listOf(source, music, once, loop).forEach(File::delete) }
    }

    @Test fun everyNoiseLevelExportsWithAndWithoutBackground() = runBlocking {
        val source = wav("speech", 48_000, 2, 2_500) { sample, channel ->
            ((0.3 + 0.1 * sin(2 * PI * 4 * sample / 48_000)) *
                (sin(2 * PI * 220 * sample / 48_000) + 0.2 * sin(2 * PI * 1_100 * sample / 48_000)) *
                (if (channel == 0) 1.0 else 0.6)).toFloat()
        }
        val output = File(context.cacheDir, "filtered.m4a")
        try {
            for (level in NoiseCancellationLevel.values().filter { it != NoiseCancellationLevel.OFF }) {
                for (withMusic in listOf(false, true)) {
                    val mixer = if (withMusic) ClipMixerOptions(Uri.fromFile(source).toString(), "Music", 40, false) else null
                    ProcessedClipExporter.export(context, source, ClipRange(500, 1_500), ClipExportOptions(level, mixer), output) {}
                    checkContainer(output, 1_000)
                    val pcm = decode(output)
                    assertTrue("Silent output at $level", rms(pcm, 200, 800) > 0.0001)
                    if (!withMusic) assertTrue("Denoised speech must be mono", channelDifference(pcm, 200, 800) < 0.003)
                    assertTrue("Missing final audio", rms(pcm, 850, 950) > 0.0001)
                }
            }
        } finally { source.delete(); output.delete() }
    }

    @Test fun discourseRemainsStereoAndEndsAtTheTrackBoundaryWithoutNoiseCancellation() = runBlocking {
        val source = wav("stereo-source", 48_000, 2, 2_000) { sample, channel ->
            (0.3 * sin(2 * PI * (if (channel == 0) 440 else 880) * sample / 48_000)).toFloat()
        }
        val output = File(context.cacheDir, "stereo.m4a")
        try {
            val options = ClipExportOptions(mixer = ClipMixerOptions(Uri.fromFile(source).toString(), "Muted music", 0, true))
            ProcessedClipExporter.export(context, source, ClipRange(1_000, 2_000), options, output) {}
            checkContainer(output, 1_000)
            val pcm = decode(output)
            assertTrue(tone(pcm, 100, 200, 0, 440.0) > 0.20)
            assertTrue(tone(pcm, 100, 200, 0, 880.0) < 0.01)
            assertTrue(tone(pcm, 100, 200, 1, 880.0) > 0.20)
            assertTrue(rms(pcm, 950, 1_000) > 0.10)
        } finally { source.delete(); output.delete() }
    }

    @Test fun settingsSurviveBundleRoundTripAndOldRequestsHaveNoEffects() {
        val expected = ClipExportOptions(NoiseCancellationLevel.MEDIUM, ClipMixerOptions("content://test/music", "Background", 37, true))
        val parcel = Parcel.obtain()
        try {
            Bundle().apply { putString("options", expected.toJson().toString()) }.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = Bundle.CREATOR.createFromParcel(parcel)
            assertEquals(expected, ClipExportOptions.fromJson(JSONObject(restored.getString("options")!!)))
            assertFalse(ClipExportOptions.fromJson(null).processed)
        } finally { parcel.recycle() }
    }

    @Test fun missingBackgroundAndCancellationFailWithoutPoisoningNextExport() = runBlocking {
        val source = wav("cancel-source", 48_000, 1, 2_000) { sample, _ -> (0.2 * sin(2 * PI * 330 * sample / 48_000)).toFloat() }
        val output = File(context.cacheDir, "retry.m4a")
        try {
            val missing = ClipExportOptions(mixer = ClipMixerOptions("content://unavailable/background", "Missing", 50, true))
            try {
                ProcessedClipExporter.export(context, source, ClipRange(0, 1_000), missing, output) {}
                fail("Missing background was silently ignored")
            } catch (_: java.io.IOException) { }
            val options = ClipExportOptions(NoiseCancellationLevel.LOW)
            try {
                ProcessedClipExporter.export(context, source, ClipRange(0, 1_000), options, output) { throw CancellationException("Test cancellation") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            ProcessedClipExporter.export(context, source, ClipRange(0, 1_000), options, output) {}
            checkContainer(output, 1_000)
        } finally { source.delete(); output.delete() }
    }

    private fun wav(name: String, rate: Int, channels: Int, duration: Int, sample: (Int, Int) -> Float): File {
        val frames = rate * duration / 1_000
        val buffer = ByteBuffer.allocate(44 + frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1.toShort()).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
        buffer.putShort((channels * 2).toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(frames * channels * 2)
        repeat(frames) { frame -> repeat(channels) { channel -> buffer.putShort((sample(frame, channel) * 32767).toInt().toShort()) } }
        return File(context.cacheDir, "$name.wav").apply { writeBytes(buffer.array()) }
    }

    private fun checkContainer(file: File, duration: Int) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
            assertEquals(48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            // AAC-LC can add two encoder-delay frames plus rounding to the last 1024-sample frame.
            assertTrue("Wrong duration: $format", abs(format.getLong(MediaFormat.KEY_DURATION) / 1_000 - duration) <= 64)
            extractor.selectTrack(0)
            var previous = -1L
            while (extractor.sampleTime >= 0) {
                assertTrue("Nonmonotonic timestamp", extractor.sampleTime > previous)
                previous = extractor.sampleTime
                extractor.advance()
            }
        } finally { extractor.release() }
    }

    private suspend fun decode(file: File): FloatArray {
        val samples = ArrayList<Float>()
        ClipAudioDecoder(context, Uri.fromFile(file), 0, null, currentCoroutineContext()).use { decoder ->
            assertEquals(48_000, decoder.sampleRate)
            val frame = FloatArray(2)
            while (decoder.next(frame)) { samples.add(frame[0]); samples.add(frame[1]) }
        }
        return samples.toFloatArray()
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Double {
        val from = start * 48 * 2
        val to = end * 48 * 2
        return sqrt((from until to).sumOf { samples[it].toDouble() * samples[it] } / (to - from))
    }

    private fun channelDifference(samples: FloatArray, start: Int, end: Int): Double =
        (start * 48 until end * 48).maxOf { abs(samples[it * 2] - samples[it * 2 + 1]).toDouble() }

    private fun tone(samples: FloatArray, start: Int, end: Int, channel: Int, frequency: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        for (frame in start * 48 until end * 48) {
            val angle = 2 * PI * frequency * frame / 48_000
            real += samples[frame * 2 + channel] * cos(angle)
            imaginary += samples[frame * 2 + channel] * sin(angle)
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / ((end - start) * 48)
    }
}
