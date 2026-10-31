package org.videolan.vlc.discourse

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaCodec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiscourseClipExporterTest {
    @Test fun variableBitrateMp3PreservesAudioAndPlayableDuration() = checkExport("variable.mp3", "audio/mpeg")
    @Test fun aacIsRemuxedIntoPlayableM4a() = checkExport("sample.m4a", "audio/mp4")

    private fun checkExport(asset: String, expectedMime: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(instrumentation.targetContext.cacheDir, "source-$asset")
        val clip = File(instrumentation.targetContext.cacheDir, "clip-$asset")
        try {
            instrumentation.context.assets.open("clips/$asset").use { input -> source.outputStream().use { input.copyTo(it) } }
            val updates = mutableListOf<Int>()
            assertEquals(expectedMime, DiscourseClipExporter.export(source, ClipRange(2_000, 4_000), clip, updates::add))
            assertTrue(clip.length() > 0)
            assertTrue(clip.length() < source.length())
            assertTrue(updates.all { it in 0..99 })
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(clip.path)
                val length = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Unexpected clip duration: $length", length in 1_950..2_350)
            } finally { metadata.release() }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(clip.path)
                assertEquals(1, extractor.trackCount)
                assertEquals(if (expectedMime == "audio/mpeg") "audio/mpeg" else "audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            } finally { extractor.release() }
            val player = MediaPlayer()
            try { player.setDataSource(clip.path); player.prepare(); assertTrue(player.duration in 1_950..2_350) }
            finally { player.release() }
            assertDecodableAudio(clip)
        } finally { source.delete(); clip.delete() }
    }

    private fun assertDecodableAudio(file: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            var decodedBytes = 0L
            var audible = false
            val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (!outputEnded && android.os.SystemClock.uptimeMillis() < deadline) {
                if (!inputEnded) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = decoder.getInputBuffer(input)!!
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        inputEnded = size < 0
                        decoder.queueInputBuffer(input, 0, size.coerceAtLeast(0), extractor.sampleTime.coerceAtLeast(0),
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputEnded) extractor.advance()
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    decodedBytes += info.size
                    val buffer = decoder.getOutputBuffer(output)!!
                    if (!audible) audible = (info.offset until info.offset + info.size).any { buffer.get(it).toInt() != 0 }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(output, false)
                }
            }
            assertTrue("Decoder did not finish", outputEnded)
            assertTrue("Clip contains too little decoded audio", decodedBytes > 150_000)
            assertTrue("Clip decoded to silence", audible)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
