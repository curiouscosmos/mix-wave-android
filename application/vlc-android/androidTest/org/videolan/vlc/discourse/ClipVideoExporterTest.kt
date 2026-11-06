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

    @Test fun lowSampleRateMp3WorksWithPhotoAndVideo() = runBlocking {
        val visual = asset("visual.mp4")
        val photo = File(context.cacheDir, "speech-photo.png")
        try {
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GREEN)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            withAudio(2_000, "speech-24k.mp3") { audio, output ->
                for (file in listOf(photo, visual)) {
                    val updates = mutableListOf<Int>()
                    ClipVideoExporter.export(context, Uri.fromFile(file), audio, output, updates::add)
                    assertEquals(100, updates.last())
                    checkTracks(audio, output, 1280, 720)
                }
            }
        } finally { listOf(photo, visual).forEach(File::delete) }
    }

    @Test fun requestRoundTripIncludesVisualAndOldRequestsDefaultToAudio() {
        val options = ClipExportOptions(NoiseCancellationLevel.HIGH,
            ClipMixerOptions("content://music/1", "Music", 37, true), ClipOutput.VIDEO, "content://photos/1", fadeIn = true, fadeOut = true)
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
        withAudio(6_000) { audio, output ->
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
        withAudio(9_000, "caption-seek-vbr.mp3") { audio, output ->
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

    @Test fun endCardFadesOverPhotoAndVideoWithWhiteMargins() = runBlocking {
        val photo = File(context.cacheDir, "end-card-source.png")
        val video = asset("visual.mp4")
        val image = context.assets.open("images/cliip-image/clip-image.png").use {
            android.graphics.BitmapFactory.decodeStream(it)!!
        }
        try {
            withAudio(6_000) { audio, output ->
                for (dimensions in listOf(400 to 200, 200 to 400, 300 to 300, null)) {
                    val source = if (dimensions == null) video else {
                        val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.RED)
                        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        photo
                    }
                    ClipVideoExporter.export(context, Uri.fromFile(source), audio, output) {}
                    val canvas = dimensions?.let { clipVideoCanvas(it.first, it.second) } ?: (1280 to 720)
                    checkTracks(audio, output, canvas.first, canvas.second)
                    withFrames(output) { retriever ->
                        // The top-left corner is outside the square image for non-square exports.
                        if (canvas.first != canvas.second) {
                            val values = listOf(0L, 1_500_000L, 2_500_000L).map { time ->
                                val frame = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                                val green = Color.green(frame.getPixel(5, 5))
                                frame.recycle()
                                green
                            }
                            assertTrue("Original visual should precede end card", values[0] < 30)
                            assertTrue("Expected partial blend: $values", values[1] in 70..190)
                            assertTrue("Expected white margin: $values", values[2] > 230)
                        }
                        for (time in listOf(2_500_000L, 5_500_000L)) {
                            val frame = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                            val size = minOf(frame.width, frame.height)
                            for (x in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
                                for (y in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
                                    val expected = image.getPixel((image.width * x).toInt(), (image.height * y).toInt())
                                    val actual = frame.getPixel(((frame.width - size) / 2 + size * x).toInt(),
                                        ((frame.height - size) / 2 + size * y).toInt())
                                    for (shift in listOf(0, 8, 16)) {
                                        assertTrue("End card orientation/scale mismatch", abs((expected shr shift and 255) - (actual shr shift and 255)) < 65)
                                    }
                                }
                            }
                            frame.recycle()
                        }
                    }
                }
            }
        } finally { image.recycle(); photo.delete(); video.delete() }
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
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)!!
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

    @Test fun videoPreservesPreparedFadedAudio() = runBlocking {
        val visual = asset("visual.mp4")
        try {
            withAudio(2_000, options = ClipExportOptions(fadeIn = true, fadeOut = true)) { audio, output ->
                ClipVideoExporter.export(context, Uri.fromFile(visual), audio, output) {}
                checkTracks(audio, output, 1280, 720)
            }
        } finally { visual.delete() }
    }

    private suspend fun withAudio(duration: Long, sourceName: String = "sample.m4a", options: ClipExportOptions = ClipExportOptions(), block: suspend (File, File) -> Unit) {
        val source = asset(sourceName)
        val audio = File(context.cacheDir, "video-prepared.m4a")
        val output = File(context.cacheDir, "video-result.mp4")
        try {
            ProcessedClipExporter.export(context, source, ClipRange(0, duration), options, audio) {}
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
