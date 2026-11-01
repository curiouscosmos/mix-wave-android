package org.videolan.vlc.discourse

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/** Local input only. The decoder owns its current output buffer until its samples are consumed. */
internal class ClipAudioDecoder(context: Context, uri: Uri, private val startMs: Long,
                                private val endMs: Long?, private val coroutine: CoroutineContext) : Closeable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var inputEnded = false
    private var outputEnded = false
    private var outputIndex = -1
    private var pcm: ByteBuffer? = null
    private var framesLeft = 0
    private var channels = 0
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var raw: ByteBuffer? = null
    private val info = MediaCodec.BufferInfo()
    var sampleRate = 0
        private set

    init {
        try {
            require(uri.scheme == "file" || uri.scheme == "content") { "Background audio must be local" }
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedClipFormat()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (mime == "audio/raw") {
                setFormat(format)
                raw = ByteBuffer.allocateDirect(1024 * 1024)
            } else {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                if (android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) == null)
                    throw UnsupportedClipFormat()
                codec = MediaCodec.createDecoderByType(mime)
                codec!!.configure(format, null, null, 0)
                codec!!.start()
            }
            extractor.seekTo(startMs * 1_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            nextBuffer()
            check(framesLeft > 0) { "No decoded audio" }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun setFormat(format: MediaFormat) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val count = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val type = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        if (rate !in 8_000..192_000 || count !in 1..2 || type !in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
            throw UnsupportedClipFormat()
        check(sampleRate == 0 || (sampleRate == rate && channels == count && encoding == type)) { "Audio format changed during export" }
        sampleRate = rate; channels = count; encoding = type
    }

    fun next(frame: FloatArray): Boolean {
        if (framesLeft == 0) nextBuffer()
        if (framesLeft == 0) return false
        val buffer = pcm!!
        fun sample(): Float = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float else buffer.short / 32768f
        frame[0] = sample()
        frame[1] = if (channels == 2) sample() else frame[0]
        check(frame[0].isFinite() && frame[1].isFinite()) { "Invalid PCM sample" }
        framesLeft--
        return true
    }

    private fun nextBuffer() {
        raw?.let { buffer ->
            while (!outputEnded && framesLeft == 0) {
                coroutine.ensureActive()
                buffer.clear()
                val pts = extractor.sampleTime
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) { outputEnded = true; break }
                acceptPcm(buffer, 0, size, pts)
                extractor.advance()
            }
            return
        }
        val decoder = codec ?: return
        if (outputIndex >= 0) decoder.releaseOutputBuffer(outputIndex, false)
        outputIndex = -1
        pcm = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!outputEnded && framesLeft == 0) {
            coroutine.ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "Audio decoder stalled" }
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val input = decoder.getInputBuffer(index)!!
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    inputEnded = size < 0
                    decoder.queueInputBuffer(index, 0, size.coerceAtLeast(0), extractor.sampleTime.coerceAtLeast(0),
                        if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (!inputEnded) extractor.advance()
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) setFormat(decoder.outputFormat)
            if (index >= 0) {
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size == 0) { decoder.releaseOutputBuffer(index, false); continue }
                if (sampleRate == 0) setFormat(decoder.outputFormat)
                acceptPcm(decoder.getOutputBuffer(index)!!, info.offset, info.size, info.presentationTimeUs)
                if (framesLeft > 0) {
                    outputIndex = index
                } else decoder.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun acceptPcm(buffer: ByteBuffer, offset: Int, size: Int, pts: Long) {
        val bytesPerFrame = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val count = size / bytesPerFrame
        val skip = (((startMs * 1_000 - pts).coerceAtLeast(0) * sampleRate + 999_999) / 1_000_000).coerceAtMost(count.toLong()).toInt()
        val end = endMs?.let {
            (((it * 1_000 - pts).coerceAtLeast(0) * sampleRate + 999_999) / 1_000_000).coerceAtMost(count.toLong()).toInt()
        } ?: count
        if (endMs != null && pts + count * 1_000_000L / sampleRate >= endMs * 1_000) outputEnded = true
        framesLeft = (end - skip).coerceAtLeast(0)
        pcm = buffer.apply {
            order(ByteOrder.nativeOrder())
            limit(offset + end * bytesPerFrame)
            position(offset + minOf(skip, end) * bytesPerFrame)
        }
    }

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        extractor.release()
    }
}
