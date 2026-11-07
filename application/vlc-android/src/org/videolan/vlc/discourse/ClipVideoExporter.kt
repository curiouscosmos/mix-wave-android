package org.videolan.vlc.discourse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/** All codec and GL work stays on the calling IO thread, without suspension points. */
internal object ClipVideoExporter {
    suspend fun export(context: Context, visual: Uri, audio: File, destination: File, ratio: ClipVideoRatio = ClipVideoRatio.ORIGINAL, progress: (Int) -> Unit): String {
        val coroutine = currentCoroutineContext()
        val sound = MediaExtractor()
        var picture: Bitmap? = null
        var endCard: Bitmap? = null
        var decoder: VideoFrames? = null
        var encoder: MediaCodec? = null
        var gl: ClipVideoSurface? = null
        var muxer: MediaMuxer? = null
        var started = false
        var complete = false
        try {
            sound.setDataSource(audio.path)
            val audioIndex = (0 until sound.trackCount).first { sound.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val audioFormat = sound.getTrackFormat(audioIndex)
            require(audioFormat.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm")
            val duration = audioFormat.getLong(MediaFormat.KEY_DURATION)
            require(duration > 0)
            sound.selectTrack(audioIndex)
            val audioStart = sound.sampleTime
            require(audioStart >= 0)

            val mime = context.contentResolver.getType(visual)
            val photo = if (mime != null) mime.startsWith("image/") else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(visual)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                bounds.outWidth > 0 && bounds.outHeight > 0
            }
            val width: Int
            val height: Int
            if (photo) {
                picture = photo(context, visual)
                width = picture.width; height = picture.height
            } else {
                decoder = VideoFrames(context, visual, coroutine)
                width = decoder.width; height = decoder.height
            }
            val (canvasWidth, canvasHeight) = clipVideoCanvas(width, height, ratio)
            endCard = endCard(context, canvasWidth, canvasHeight)
            val format = MediaFormat.createVideoFormat("video/avc", canvasWidth, canvasHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            encoder = MediaCodec.createEncoderByType("video/avc")
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            gl = ClipVideoSurface(encoder.createInputSurface(), picture, canvasWidth, canvasHeight, width, height, endCard)
            decoder?.start(gl.decoderSurface!!)
            encoder.start()
            muxer = MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val audioTrack = muxer.addTrack(audioFormat)
            var videoTrack = -1
            val info = MediaCodec.BufferInfo()
            var ended = false
            var lastVideoPts = -1L
            fun drain(wait: Long = 0): Boolean {
                var received = false
                while (true) {
                    coroutine.ensureActive()
                    val index = encoder.dequeueOutputBuffer(info, wait)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!started)
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                    } else if (index >= 0) {
                        received = true
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(started && info.presentationTimeUs > lastVideoPts) { "Nonmonotonic video encoder output" }
                                lastVideoPts = info.presentationTimeUs
                                muxer.writeSampleData(videoTrack, encoder.getOutputBuffer(index)!!, info)
                            }
                            ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(index, false) }
                        if (ended) break
                    } else break
                }
                return received
            }
            val frames = (duration * 30 + 999_999) / 1_000_000
            var lastPercent = -1
            for (frame in 0 until frames) {
                coroutine.ensureActive()
                val pts = frame * 1_000_000 / 30
                decoder?.render(pts, gl)
                gl.draw(pts, clipEndCardOpacity(pts, duration))
                drain()
                val percent = (frame * 99 / frames).toInt()
                if (percent != lastPercent) { progress(percent); lastPercent = percent }
            }
            encoder.signalEndOfInputStream()
            var deadline = SystemClock.elapsedRealtime() + 15_000
            while (!ended) {
                check(SystemClock.elapsedRealtime() < deadline) { "Video encoder stalled" }
                if (drain(10_000)) deadline = SystemClock.elapsedRealtime() + 15_000
            }
            check(started && lastVideoPts >= 0)
            // Copy only the prepared AAC track; the selected video's audio is never read.
            val buffer = ByteBuffer.allocateDirect(maxOf(256 * 1024,
                if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0))
            var previous = -1L
            while (sound.sampleTime >= 0) {
                coroutine.ensureActive()
                buffer.clear()
                val size = sound.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = sound.sampleTime - audioStart
                check(pts > previous)
                info.set(0, size, pts, sound.sampleFlags)
                muxer.writeSampleData(audioTrack, buffer, info)
                previous = pts
                sound.advance()
            }
            muxer.stop()
            started = false
            progress(100)
            complete = true
            return "video/mp4"
        } finally {
            decoder?.close()
            encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            gl?.close()
            picture?.recycle()
            endCard?.recycle()
            sound.release()
            muxer?.let { if (started) runCatching { it.stop() }; runCatching { it.release() } }
            if (!complete) destination.delete()
        }
    }

    private fun endCard(context: Context, width: Int, height: Int): Bitmap {
        val image = context.assets.open("images/cliip-image/clip-image.png").use {
            BitmapFactory.decodeStream(it) ?: error("Cannot decode clip end card")
        }
        try {
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
                val left = (width - image.width * scale) / 2f
                val top = (height - image.height * scale) / 2f
                Canvas(result).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(image, null, RectF(left, top, width - left, height - top), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                return result
            } catch (error: Throwable) { result.recycle(); throw error }
        } finally { image.recycle() }
    }

    private fun photo(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported photo" }
        val options = BitmapFactory.Options()
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 2560)
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode photo")
        try {
            require(original.colorSpace?.isWideGamut != true && (Build.VERSION.SDK_INT < 34 || !original.hasGainmap())) { "HDR photos are unsupported" }
            val orientation = context.contentResolver.openFileDescriptor(uri, "r")?.use {
                ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: error("Cannot read photo orientation")
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
                val scale = minOf(1f, 1280f / maxOf(original.width, original.height))
                postScale(scale, scale)
            }
            val result = Bitmap.createBitmap(original, 0, 0, original.width, original.height, transform, true)
            if (result !== original) original.recycle()
            return result
        } catch (error: Throwable) { original.recycle(); throw error }
    }

    private class VideoFrames(context: Context, uri: Uri, private val coroutine: CoroutineContext) : Closeable {
        private val extractor = MediaExtractor()
        private var codec: MediaCodec? = null
        private val info = MediaCodec.BufferInfo()
        private lateinit var format: MediaFormat
        val width: Int
        val height: Int
        private val duration: Long
        private val origin: Long
        private var inputEnded = false
        private var outputEnded = false
        private var pending = -1
        private var hasFrame = false
        private var cycle = -1L

        init {
            try {
                extractor.setDataSource(context, uri, null)
                val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: error("No supported video track")
                format = extractor.getTrackFormat(track)
                checkSdr(format)
                val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                require(rotation in listOf(0, 90, 180, 270))
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                width = if (rotation % 180 == 0) w else h
                height = if (rotation % 180 == 0) h else w
                duration = format.getLong(MediaFormat.KEY_DURATION)
                require(width > 0 && height > 0 && duration > 0)
                // Surface decoders apply rotation metadata before SurfaceTexture's transform.
                extractor.selectTrack(track)
                origin = extractor.sampleTime
                require(origin >= 0)
            } catch (error: Throwable) { close(); throw error }
        }

        fun start(surface: android.view.Surface) {
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec!!.configure(format, surface, null, 0)
            codec!!.start()
        }

        fun render(time: Long, gl: ClipVideoSurface) {
            val nextCycle = time / duration
            if (cycle != nextCycle) {
                if (cycle >= 0) {
                    if (pending >= 0) codec!!.releaseOutputBuffer(pending, false)
                    pending = -1
                    codec!!.flush()
                    extractor.seekTo(origin, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    inputEnded = false; outputEnded = false; hasFrame = false
                }
                cycle = nextCycle
            }
            val target = time % duration
            var deadline = SystemClock.elapsedRealtime() + 15_000
            while (!outputEnded) {
                coroutine.ensureActive()
                check(SystemClock.elapsedRealtime() < deadline) { "Video decoder stalled" }
                if (pending >= 0) {
                    if (hasFrame && info.presentationTimeUs - origin > target) break
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0
                    codec!!.releaseOutputBuffer(pending, render)
                    pending = -1
                    if (render) { gl.awaitFrame(coroutine); hasFrame = true }
                    outputEnded = eos
                    deadline = SystemClock.elapsedRealtime() + 15_000
                    continue
                }
                if (!inputEnded) {
                    val input = codec!!.dequeueInputBuffer(0)
                    if (input >= 0) {
                        val size = extractor.readSampleData(codec!!.getInputBuffer(input)!!, 0)
                        inputEnded = size < 0
                        codec!!.queueInputBuffer(input, 0, maxOf(0, size), if (inputEnded) 0 else extractor.sampleTime,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputEnded) extractor.advance()
                    }
                }
                val output = codec!!.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) pending = output
                else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) checkSdr(codec!!.outputFormat)
            }
            check(hasFrame) { "Empty video" }
        }

        override fun close() {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            codec = null
            extractor.release()
        }
    }

    private fun checkSdr(format: MediaFormat) {
        val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
        val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
        require(transfer != MediaFormat.COLOR_TRANSFER_ST2084 && transfer != MediaFormat.COLOR_TRANSFER_HLG &&
            standard != MediaFormat.COLOR_STANDARD_BT2020 && !format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) &&
            format.getString(MediaFormat.KEY_MIME) != "video/dolby-vision") { "HDR video is unsupported" }
    }
}

internal fun clipVideoCanvas(width: Int, height: Int, ratio: ClipVideoRatio = ClipVideoRatio.ORIGINAL): Pair<Int, Int> {
    require(width > 0 && height > 0)
    return when (ratio) {
        ClipVideoRatio.PORTRAIT -> 720 to 1280
        ClipVideoRatio.LANDSCAPE -> 1280 to 720
        ClipVideoRatio.ORIGINAL -> {
            val scale = 1280.0 / maxOf(width, height)
            fun even(value: Int) = ((value * scale / 2).toInt() * 2).coerceAtLeast(2)
            even(width) to even(height)
        }
    }
}

internal fun clipVideoViewport(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): IntArray {
    val scale = maxOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
    val w = kotlin.math.ceil(sourceWidth * scale).toInt()
    val h = kotlin.math.ceil(sourceHeight * scale).toInt()
    return intArrayOf((width - w) / 2, (height - h) / 2, w, h)
}

internal fun clipEndCardOpacity(timeUs: Long, durationUs: Long): Float {
    require(durationUs > 0)
    val start = (durationUs - 5_000_000).coerceAtLeast(0)
    val fade = minOf(1_000_000L, durationUs)
    return ((timeUs - start).toFloat() / fade).coerceIn(0f, 1f)
}
