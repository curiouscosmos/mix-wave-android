package org.videolan.vlc.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.kaleyra.noise_filter.dispatcher.DispatcherProvider
import com.kaleyra.noise_filter.model_loader.DeepFilterModelLoader
import com.rikorose.deepfilternet.NativeDeepFilterNet
import kotlinx.coroutines.Dispatchers
import org.videolan.libvlc.MediaPlayer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owned by one stopped/restarted native player. All inference runs on VLC's audio thread. */
@Keep
class NoiseOutput private constructor(private val context: Context, private val onFailure: () -> Unit) {
    private val modelBytes = context.resources.openRawResource(com.kaleyra.noise_filter.R.raw.deep_filter_mobile_model).use { it.readBytes() }
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val outputLock = Any()
    private val processingLock = Any()
    private val generation = AtomicLong()
    private val failed = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var paused = false
    private var processingGeneration = 0L
    private var writtenSamples = 0L
    @Volatile internal var processedSamples = 0L
        private set
    internal val underruns: Int get() = track.underrunCount
    @Volatile private var gain = 1f
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(5760, AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build().also {
            if (it.state != AudioTrack.STATE_INITIALIZED) {
                it.release()
                error("Audio output initialization failed")
            }
        }
    private var filter = try { newFilter() } catch (error: Throwable) { track.release(); throw error }
    private var frames = PcmFrames(filter.frameLength.toInt())
    private var handle = 0L
    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { invalidate() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { invalidate() }
    }

    // The upstream default constructor starts an unowned asynchronous load and swallows errors.
    // Inline dispatch plus an already loaded model makes initialization synchronous on our IO caller.
    private fun newFilter() = NativeDeepFilterNet(context, dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val mainImmediate = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }, modelLoader = object : DeepFilterModelLoader {
        override suspend fun load(context: Context) = modelBytes
    }).also {
        if (it.frameLength !in 2..96000 || it.frameLength % 2 != 0L) {
            it.release()
            error("Noise model initialization failed")
        }
    }

    fun install(player: MediaPlayer) {
        check(handle == 0L)
        handle = attach(player.instance)
        check(handle != 0L) { "LibVLC audio callback API unavailable" }
        audioManager.registerAudioDeviceCallback(routeCallback, Handler(Looper.getMainLooper()))
    }

    private fun valid(epoch: Long) = !closed && !failed.get() && !paused && generation.get() == epoch

    @Keep
    fun onAudio(source: ByteBuffer, pts: Long) = guarded {
        val epoch = generation.get()
        synchronized(processingLock) {
            if (!valid(epoch)) return@guarded
            if (processingGeneration != epoch) {
                filter.release()
                filter = newFilter()
                frames = PcmFrames(filter.frameLength.toInt())
                processingGeneration = epoch
            }
            var offset = 0L
            frames.append(source) { frame, count ->
                if (valid(epoch)) {
                    // pts uses libvlc_clock's monotonic microseconds, not Android wall time.
                    val deadline = pts + offset - 40000
                    val waitStart = SystemClock.elapsedRealtime()
                    while (valid(epoch) && clock() < deadline) {
                        check(SystemClock.elapsedRealtime() - waitStart < 3000) { "Invalid audio timestamp" }
                        SystemClock.sleep(2)
                    }
                    if (valid(epoch)) process(frame, count, epoch)
                }
                offset += count * 1000000L / 96000
            }
        }
    }

    private fun process(frame: ByteBuffer, count: Int, epoch: Long) {
        check(filter.processFrame(frame).isFinite()) { "Noise processing failed" }
        processedSamples += count / 2
        frame.position(0)
        frame.limit(count)
        val amplification = gain
        if (amplification > 1f) {
            for (index in 0 until count step 2) {
                frame.putShort(index, (frame.getShort(index) * amplification).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        val started = SystemClock.elapsedRealtime()
        while (frame.hasRemaining() && valid(epoch)) {
            synchronized(outputLock) {
                if (valid(epoch)) {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                    val written = track.write(frame, frame.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0) { "Audio output failed: $written" }
                    writtenSamples += written / 2
                }
            }
            check(SystemClock.elapsedRealtime() - started < 1000) { "Audio output stalled" }
            if (frame.hasRemaining()) SystemClock.sleep(2)
        }
    }

    private fun invalidate() {
        synchronized(outputLock) {
            generation.incrementAndGet()
            if (!closed) {
                track.pause()
                track.flush()
                writtenSamples = 0
            }
        }
    }

    @Keep
    fun onControl(action: Int) = guarded {
        when (action) {
            0 -> synchronized(processingLock) {
                synchronized(outputLock) { paused = true; track.pause() }
            }
            1 -> synchronized(outputLock) { paused = false; track.play() }
            2 -> invalidate()
            4 -> error("Native audio callback failed")
            3 -> synchronized(processingLock) {
                val epoch = generation.get()
                if (processingGeneration == epoch && valid(epoch)) {
                    frames.drain { frame, count -> if (valid(epoch)) process(frame, count, epoch) }
                    // Bundled 0.0.8 model: fft=960, hop=480, no lookahead; flush its one-frame STFT delay.
                    frames.buffer.clear()
                    while (frames.buffer.hasRemaining()) frames.buffer.put(0)
                    frames.buffer.flip()
                    if (valid(epoch)) process(frames.buffer, frames.size, epoch)
                    val started = SystemClock.elapsedRealtime()
                    while (valid(epoch) && synchronized(outputLock) { (track.playbackHeadPosition.toLong() and 0xffffffffL) < writtenSamples }) {
                        check(SystemClock.elapsedRealtime() - started < 1000) { "Audio drain stalled" }
                        SystemClock.sleep(2)
                    }
                }
            }
        }
    }

    @Keep
    fun onGain(volume: Float, muted: Boolean) = guarded {
        synchronized(outputLock) {
            gain = if (muted) 0f else volume.coerceIn(0f, 4f)
            if (!closed) track.setVolume(gain.coerceAtMost(1f))
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try { if (!closed && !failed.get()) block() }
        catch (error: Exception) { Log.e("NoiseOutput", "Audio filtering failed", error); fail() }
        catch (error: LinkageError) { Log.e("NoiseOutput", "Audio filtering failed", error); fail() }
    }

    private fun fail() {
        if (failed.compareAndSet(false, true)) {
            invalidate()
            Handler(Looper.getMainLooper()).post { if (!closed) onFailure() }
        }
    }

    /** Invalidate first so stop can join callbacks even when they are waiting for AudioTrack. */
    fun cancel() { paused = true; invalidate() }

    /** The owning player MUST be stopped before this call. */
    fun close() {
        if (closed) return
        cancel()
        if (handle != 0L) { detach(handle); handle = 0L }
        audioManager.unregisterAudioDeviceCallback(routeCallback)
        synchronized(processingLock) { filter.release() }
        synchronized(outputLock) { closed = true; track.release() }
    }

    private external fun attach(player: Long): Long
    private external fun detach(handle: Long)
    private external fun clock(): Long

    companion object {
        fun create(context: Context, onFailure: () -> Unit): NoiseOutput {
            System.loadLibrary("discourse_audio")
            return NoiseOutput(context.applicationContext, onFailure)
        }
    }
}
