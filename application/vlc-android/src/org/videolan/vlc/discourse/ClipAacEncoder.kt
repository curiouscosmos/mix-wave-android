package org.videolan.vlc.discourse

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

internal class ClipAacEncoder(destination: File, private val coroutine: CoroutineContext) : Closeable {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var frames = 0L
    private var ended = false
    private val pending = FloatArray(1024 * 2)
    private var pendingFrames = 0
    private val info = MediaCodec.BufferInfo()

    init {
        try {
            muxer = MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48_000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec!!.start()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun write(samples: FloatArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val size = minOf(count - offset, 1024 - pendingFrames)
            samples.copyInto(pending, pendingFrames * 2, offset * 2, (offset + size) * 2)
            pendingFrames += size
            offset += size
            // AAC encoders derive output timestamps more reliably from complete 1024-sample frames.
            if (pendingFrames == 1024) { queue(pending, pendingFrames); pendingFrames = 0 }
        }
    }

    private fun queue(samples: FloatArray, count: Int) {
        var offset = 0
        var deadline = SystemClock.elapsedRealtime() + 10_000
        val encoder = codec!!
        while (offset < count) {
            coroutine.ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "Audio encoder stalled" }
            drain()
            val index = encoder.dequeueInputBuffer(10_000)
            if (index < 0) continue
            val input = encoder.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.nativeOrder()) }
            val size = minOf(count - offset, input.remaining() / 4)
            check(size > 0)
            for (sample in offset * 2 until (offset + size) * 2) {
                input.putShort((samples[sample].coerceIn(-1f, 1f) * 32767).toInt().toShort())
            }
            encoder.queueInputBuffer(index, 0, size * 4, frames * 1_000_000 / 48_000, 0)
            frames += size
            offset += size
            deadline = SystemClock.elapsedRealtime() + 10_000
        }
    }

    fun finish() {
        if (pendingFrames > 0) { queue(pending, pendingFrames); pendingFrames = 0 }
        val encoder = codec!!
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var queued = false
        while (!ended) {
            coroutine.ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "Audio encoder drain stalled" }
            if (!queued) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    encoder.queueInputBuffer(index, 0, 0, frames * 1_000_000 / 48_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                }
            }
            drain(10_000)
        }
        check(track >= 0 && frames > 0)
        muxer!!.stop()
        track = -1
    }

    private fun drain(waitUs: Long = 0) {
        val encoder = codec!!
        while (true) {
            coroutine.ensureActive()
            val index = encoder.dequeueOutputBuffer(info, waitUs)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(track < 0)
                track = muxer!!.addTrack(encoder.outputFormat)
                muxer!!.start()
            } else if (index >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(track >= 0)
                        val buffer = encoder.getOutputBuffer(index)!!.apply {
                            position(info.offset); limit(info.offset + info.size)
                        }
                        muxer!!.writeSampleData(track, buffer, info)
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } finally { encoder.releaseOutputBuffer(index, false) }
                if (ended) return
            } else return
        }
    }

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        muxer?.let { if (track >= 0) runCatching { it.stop() }; runCatching { it.release() } }
        muxer = null
    }
}
