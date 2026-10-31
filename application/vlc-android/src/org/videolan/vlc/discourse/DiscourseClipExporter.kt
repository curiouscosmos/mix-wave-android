package org.videolan.vlc.discourse

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodec
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class UnsupportedClipFormat : Exception()

/** Copies compressed samples. No decoder, encoder, playback effects or remote processing. */
internal object DiscourseClipExporter {
    suspend fun export(source: File, range: ClipRange, destination: File, progress: (Int) -> Unit): String {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedClipFormat()
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
            val mp3 = mime == "audio/mpeg"
            if (!mp3 && mime != "audio/mp4a-latm") throw UnsupportedClipFormat()
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000 else range.endMs
            require(range.isValid(durationMs + 100)) { "Invalid clip range" }
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var outputTrack = -1
            if (!mp3) {
                muxer = MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                outputTrack = muxer.addTrack(format)
                muxer.start()
                muxerStarted = true
                extractor.seekTo(range.startMs * 1_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            var samples = 0
            var firstTimestamp = -1L
            val history = ArrayDeque<ByteArray>()
            var lastPercent = -1
            val raw = if (mp3) RandomAccessFile(destination, "rw").apply { setLength(0) } else null
            var mp3Frames = 0
            var xingOffset = 0
            try {
                while (extractor.sampleTime >= 0 && extractor.sampleTime < range.endMs * 1_000) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val time = extractor.sampleTime
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    require(size <= buffer.capacity())
                    if (mp3) {
                        val frame = ByteArray(size)
                        buffer.position(0)
                        buffer.get(frame)
                        if (time < range.startMs * 1_000) {
                            history.addLast(frame)
                            if (history.size > 64) history.removeFirst()
                        } else {
                            if (samples == 0) {
                                // Layer III frames can reference up to 511 bytes from earlier frames.
                                val preroll = mp3Preroll(history.toList(), frame)
                                val header = mp3XingHeader(preroll.firstOrNull() ?: frame)
                                raw!!.write(header)
                                xingOffset = mp3SideInfoEnd(header)
                                preroll.forEach { raw.write(it); mp3Frames++ }
                            }
                            raw!!.write(frame)
                            mp3Frames++
                            samples++
                        }
                    } else if (time >= range.startMs * 1_000) {
                        if (firstTimestamp < 0) firstTimestamp = time
                        buffer.position(0)
                        buffer.limit(size)
                        muxer!!.writeSampleData(outputTrack, buffer, MediaCodec.BufferInfo().apply {
                            set(0, size, time - firstTimestamp, extractor.sampleFlags)
                        })
                        samples++
                    }
                    val percent = ((time / 1_000 - range.startMs).coerceAtLeast(0) * 100 / (range.endMs - range.startMs)).toInt().coerceIn(0, 99)
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                    extractor.advance()
                }
                if (mp3 && mp3Frames > 0) {
                    val bytes = raw!!.length()
                    require(bytes <= 0xFFFFFFFFL) { "MP3 clip is too large" }
                    raw.seek((xingOffset + 8).toLong())
                    raw.writeInt(mp3Frames)
                    raw.writeInt(bytes.toInt())
                }
            } finally { raw?.close() }
            check(samples > 0) { "No audio samples in selected range" }
            if (muxerStarted) { muxer!!.stop(); muxerStarted = false }
            return if (mp3) "audio/mpeg" else "audio/mp4"
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
            extractor.release()
        }
    }
}

private fun mp3SideInfoEnd(frame: ByteArray): Int {
    val v1 = (frame[1].toInt() and 0x18) == 0x18
    val mono = (frame[3].toInt() and 0xC0) == 0xC0
    val sideSize = if (v1) { if (mono) 17 else 32 } else { if (mono) 9 else 17 }
    return (if ((frame[1].toInt() and 1) == 0) 6 else 4) + sideSize
}

/** Fresh duration/byte-count metadata, rather than retaining the full track's Xing/VBRI header. */
internal fun mp3XingHeader(reference: ByteArray): ByteArray {
    if (reference.size < 4 || reference[0].toInt() and 255 != 255 || reference[1].toInt() and 6 != 2)
        throw UnsupportedClipFormat()
    val version = (reference[1].toInt() shr 3) and 3
    val rateIndex = (reference[2].toInt() shr 2) and 3
    if (version == 1 || rateIndex == 3) throw UnsupportedClipFormat()
    val rate = intArrayOf(44_100, 48_000, 32_000)[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
    val size = (if (version == 3) 144 * 320_000 else 72 * 160_000) / rate
    return ByteArray(size).apply {
        reference.copyInto(this, endIndex = 4)
        this[1] = (this[1].toInt() or 1).toByte() // No CRC on the metadata frame.
        this[2] = ((this[2].toInt() and 0x0D) or 0xE0).toByte()
        val offset = mp3SideInfoEnd(this)
        "Xing".toByteArray(Charsets.US_ASCII).copyInto(this, offset)
        ByteBuffer.wrap(this).putInt(offset + 4, 3) // Frame count and byte count follow.
    }
}

/** Return the minimum preceding frames supplying the first selected frame's bit reservoir. */
internal fun mp3Preroll(history: List<ByteArray>, first: ByteArray): List<ByteArray> {
    if (first.size < 6) return emptyList()
    val version1 = (first[1].toInt() and 0x18) == 0x18
    val sideOffset = if ((first[1].toInt() and 1) == 0) 6 else 4
    if (first.size <= sideOffset + 1) return emptyList()
    var needed = if (version1) ((first[sideOffset].toInt() and 255) shl 1) or ((first[sideOffset + 1].toInt() and 255) shr 7)
        else first[sideOffset].toInt() and 255
    var index = history.size
    while (needed > 0 && index > 0) {
        val frame = history[--index]
        if (frame.size < 6) continue
        val v1 = (frame[1].toInt() and 0x18) == 0x18
        val mono = (frame[3].toInt() and 0xC0) == 0xC0
        val sideSize = if (v1) { if (mono) 17 else 32 } else { if (mono) 9 else 17 }
        val headerSize = if ((frame[1].toInt() and 1) == 0) 6 else 4
        needed -= (frame.size - headerSize - sideSize).coerceAtLeast(0)
    }
    return history.drop(index)
}
