package org.videolan.vlc.discourse

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.vlc.audio.NoiseCancellationLevel
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ClipVideoExporterTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun requestRoundTripIncludesVisualAndOldRequestsDefaultToAudio() {
        val options = ClipExportOptions(NoiseCancellationLevel.HIGH,
            ClipMixerOptions("content://music/1", "Music", 37, true), ClipOutput.VIDEO, "content://photos/1")
        assertEquals(options, ClipExportOptions.fromJson(JSONObject(options.toJson().toString())))
        assertEquals(ClipOutput.AUDIO, ClipExportOptions.fromJson(JSONObject("{\"noise\":\"OFF\"}")).output)
        assertEquals(ClipOutput.AUDIO, ClipExportOptions.fromJson(null).output)
    }

    @Test fun stillPhotoRespectsExifAndAspectRatio() = runBlocking {
        val photo = File(context.cacheDir, "visual.jpg")
        val bitmap = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        android.graphics.Canvas(bitmap).drawRect(1200f, 0f, 2400f, 1200f, android.graphics.Paint().apply { color = Color.YELLOW })
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        withAudio(1_000) { audio, output ->
            try {
                ClipVideoExporter.export(context, Uri.fromFile(photo), audio, output) {}
                checkTracks(audio, output, 720, 1280)
                withFrames(output) { retriever ->
                    for (time in listOf(0L, 800_000L)) {
                        val frame = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                        assertTrue(Color.green(frame.getPixel(360, 640)) > 180)
                        assertTrue("Expected black pillarbox", Color.green(frame.getPixel(5, 640)) < 30)
                        assertTrue("EXIF rotation or vertical orientation lost", Color.red(frame.getPixel(360, 300)) < 40)
                        assertTrue("EXIF rotation or vertical orientation lost", Color.red(frame.getPixel(360, 1000)) > 180)
                        frame.recycle()
                    }
                }
            } finally { photo.delete() }
        }
    }

    @Test fun videoLoopsAndDiscardsOriginalSound() = runBlocking {
        val visual = asset("visual.mp4")
        withAudio(4_000) { audio, output ->
            try {
                ClipVideoExporter.export(context, Uri.fromFile(visual), audio, output) {}
                checkTracks(audio, output, 1280, 720)
                withFrames(output) { retriever ->
                    for ((time, red) in listOf(300_000L to true, 1_300_000L to false, 2_300_000L to true, 3_300_000L to false)) {
                        val frame = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                        val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
                        assertTrue("Wrong loop frame at $time", if (red) Color.red(pixel) > 180 && Color.blue(pixel) < 40 else Color.blue(pixel) > 180 && Color.red(pixel) < 40)
                        frame.recycle()
                    }
                }
            } finally { visual.delete() }
        }
    }

    @Test fun landscapeAndSquarePngPhotosUseMatchingCanvases() = runBlocking {
        val photo = File(context.cacheDir, "visual.png")
        withAudio(1_000) { audio, output ->
            try {
                for ((width, height) in listOf(400 to 200, 300 to 300)) {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.BLUE)
                    photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    ClipVideoExporter.export(context, Uri.fromFile(photo), audio, output) {}
                    val canvas = clipVideoCanvas(width, height)
                    checkTracks(audio, output, canvas.first, canvas.second)
                }
            } finally { photo.delete() }
        }
    }

    @Test fun rotatedVideoTrimsAndCancellationAllowsRetry() = runBlocking {
        val visual = asset("rotated.mp4")
        withAudio(1_000) { audio, output ->
            try {
                try {
                    ClipVideoExporter.export(context, Uri.fromFile(visual), audio, output) { throw CancellationException("Cancel") }
                    fail("Cancellation was ignored")
                } catch (_: CancellationException) { assertFalse(output.exists()) }
                ClipVideoExporter.export(context, Uri.fromFile(visual), audio, output) {}
                checkTracks(audio, output, 720, 1280)
                withFrames(output) { retriever ->
                    val frame = retriever.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                    assertTrue("Rotation metadata lost", Color.green(frame.getPixel(100, 1100)) > 180)
                    assertTrue("Rotation metadata lost", Color.red(frame.getPixel(100, 100)) > 180)
                    frame.recycle()
                }
            } finally { visual.delete() }
        }
    }

    @Test fun missingAndUnsupportedVisualFailWithoutOutput() = runBlocking {
        withAudio(1_000) { audio, output ->
            for (file in listOf(File(context.cacheDir, "missing-visual"), File(context.cacheDir, "bad-visual").apply { writeText("unsupported") })) {
                try {
                    try {
                        ClipVideoExporter.export(context, Uri.fromFile(file), audio, output) {}
                        fail("Invalid visual was accepted")
                    } catch (_: Exception) { assertFalse(output.exists()) }
                } finally { file.delete() }
            }
        }
    }

    @Test fun hdrIsRejectedBeforeRendering() = runBlocking {
        val visual = asset("hdr.mp4")
        withAudio(1_000) { audio, output ->
            try {
                try {
                    ClipVideoExporter.export(context, Uri.fromFile(visual), audio, output) {}
                    fail("HDR was accepted")
                } catch (error: IllegalArgumentException) {
                    assertTrue(error.message.orEmpty().contains("HDR"))
                    assertFalse(output.exists())
                }
            } finally { visual.delete() }
        }
    }

    private suspend fun withAudio(duration: Long, block: suspend (File, File) -> Unit) {
        val source = asset("sample.m4a")
        val audio = File(context.cacheDir, "video-prepared.m4a")
        val output = File(context.cacheDir, "video-result.mp4")
        try {
            ProcessedClipExporter.export(context, source, ClipRange(0, duration), ClipExportOptions(), audio) {}
            block(audio, output)
        } finally { listOf(source, audio, output).forEach(File::delete) }
    }

    private fun asset(name: String) = File(context.cacheDir, "test-$name").apply {
        instrumentation.context.assets.open("clips/$name").use { input -> outputStream().use { input.copyTo(it) } }
    }

    private fun withFrames(file: File, block: (MediaMetadataRetriever) -> Unit) {
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(file.path); block(retriever) } finally { retriever.release() }
    }

    private fun checkTracks(audio: File, video: File, width: Int, height: Int) {
        val prepared = MediaExtractor()
        val result = MediaExtractor()
        try {
            prepared.setDataSource(audio.path)
            result.setDataSource(video.path)
            assertEquals(2, result.trackCount)
            var audioTrack = -1
            var audioDuration = 0L
            var videoDuration = 0L
            for (track in 0 until result.trackCount) {
                val format = result.getTrackFormat(track)
                if (format.getString(MediaFormat.KEY_MIME) == "video/avc") {
                    assertEquals(width, format.getInteger(MediaFormat.KEY_WIDTH))
                    assertEquals(height, format.getInteger(MediaFormat.KEY_HEIGHT))
                    videoDuration = format.getLong(MediaFormat.KEY_DURATION)
                    result.selectTrack(track)
                    var previous = -1L
                    while (result.sampleTime >= 0) {
                        assertTrue(result.sampleTime > previous)
                        previous = result.sampleTime
                        result.advance()
                    }
                    result.unselectTrack(track)
                } else {
                    assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
                    audioTrack = track
                    audioDuration = format.getLong(MediaFormat.KEY_DURATION)
                }
            }
            assertTrue("A/V duration difference ${videoDuration - audioDuration}", abs(videoDuration - audioDuration) <= 33_334)
            prepared.selectTrack(0)
            result.selectTrack(audioTrack)
            result.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val a = ByteBuffer.allocate(64 * 1024)
            val b = ByteBuffer.allocate(64 * 1024)
            while (prepared.sampleTime >= 0) {
                assertEquals(prepared.sampleTime, result.sampleTime)
                a.clear(); b.clear()
                val size = prepared.readSampleData(a, 0)
                assertEquals(size, result.readSampleData(b, 0))
                val expected = ByteArray(size); val actual = ByteArray(size)
                a.position(0); a.get(expected); b.position(0); b.get(actual)
                assertArrayEquals("Output must use prepared clip audio only", expected, actual)
                prepared.advance(); result.advance()
            }
            assertEquals(-1L, result.sampleTime)
        } finally { prepared.release(); result.release() }
    }
}
