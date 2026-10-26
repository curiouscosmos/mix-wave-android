package org.videolan.vlc.audio

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.resources.AppContextProvider
import org.videolan.resources.VLCInstance
import org.videolan.vlc.media.PlayerController
import org.videolan.vlc.media.MediaPlayerEventListener
import com.rikorose.deepfilternet.NativeDeepFilterNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Random
import kotlin.math.sin

/** Exercises the packaged model and the actual LibVLC callbacks, not mocked JNI. */
@RunWith(AndroidJUnit4::class)
class NoiseOutputTest {
    @Test fun levelsChangeLiveSurviveSeekAndResetOnTrackChange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AppContextProvider.init(context)
        val source = waveFile("noise-levels-test.wav")
        lateinit var controller: PlayerController
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        val listener = object : MediaPlayerEventListener {
            override suspend fun onEvent(event: MediaPlayer.Event) {}
        }
        fun start() = runBlocking {
            withContext(Dispatchers.Main) {
                val media = Media(VLCInstance.getInstance(context), Uri.fromFile(source))
                try { controller.startPlayback(media, listener, 0) } finally { media.release() }
            }
        }
        main { controller = PlayerController(context) }
        try {
            start()
            await { controller.getCurrentTime() > 500 }
            val output = NoiseOutput.create(context, NoiseCancellationLevel.LOW) { fail("Unexpected filter failure") }
            main { controller.setVolume(37); assertTrue(controller.changeNoiseOutput(output)) }
            await { output.processedSamples > 48000 }
            val player = controller.mediaplayer
            for (level in listOf(NoiseCancellationLevel.MEDIUM, NoiseCancellationLevel.HIGH, NoiseCancellationLevel.LOW)) {
                val processed = output.processedSamples
                main { assertTrue(controller.setNoiseCancellationLevel(level)) }
                await { output.appliedLevel == level && output.processedSamples > processed }
                assertSame("Changing level restarted the player", player, controller.mediaplayer)
                assertEquals(37, controller.getVolume())
            }
            main { controller.setTime(5000) }
            await { controller.getCurrentTime() > 5500 && output.appliedLevel == NoiseCancellationLevel.LOW }
            main { controller.pause() }
            await { controller.isPaused() }
            main { assertTrue(controller.setNoiseCancellationLevel(NoiseCancellationLevel.HIGH)) }
            assertTrue(controller.isPaused())
            main { controller.play() }
            await { output.appliedLevel == NoiseCancellationLevel.HIGH && controller.getCurrentTime() > 6000 }
            assertSame(player, controller.mediaplayer)
            val reset = CountDownLatch(1)
            main { controller.onNoiseReset = { reset.countDown() } }
            start()
            assertTrue("Track transition did not reset cancellation", reset.await(5, TimeUnit.SECONDS))
            main { assertFalse(controller.setNoiseCancellationLevel(NoiseCancellationLevel.MEDIUM)) }
            await { controller.getCurrentTime() > 500 }
        } finally {
            main { controller.stop(); controller.release() }
            source.delete()
        }
    }

    @Test fun ordinaryOutputReturnsAfterDisableAndTrackEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AppContextProvider.init(context)
        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val source = waveFile("noise-output-transition.wav")
        lateinit var controller: PlayerController
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        val listener = object : MediaPlayerEventListener {
            override suspend fun onEvent(event: MediaPlayer.Event) {}
        }
        fun start() = runBlocking(Dispatchers.Main) {
            val media = Media(VLCInstance.getInstance(context), Uri.fromFile(source))
            try { controller.startPlayback(media, listener, 0) } finally { media.release() }
        }
        fun assertOutput(expectedVolume: Int) {
            await { controller.mediaplayer.time > 500 }
            await(5000) { manager.activePlaybackConfigurations.isNotEmpty() }
            assertEquals("Ordinary output volume was lost", expectedVolume, controller.getVolume())
        }
        main { controller = PlayerController(context) }
        try {
            start()
            main { assertEquals(0, controller.setVolume(65)) }
            assertOutput(65)
            repeat(2) { transition ->
                val output = NoiseOutput.create(context) { fail("Unexpected filter failure") }
                main { assertTrue(controller.changeNoiseOutput(output)) }
                await { output.processedSamples > 48000 }
                assertEquals(65, controller.getVolume())
                main { assertEquals(0, controller.setVolume(37)) }
                assertEquals(37, controller.getVolume())
                if (transition == 0) {
                    main { assertTrue(controller.changeNoiseOutput(null)) }
                } else {
                    val ended = CountDownLatch(1)
                    main { controller.onNoiseReset = { ended.countDown() }; controller.setTime(34000) }
                    assertTrue("Track did not end", ended.await(8, TimeUnit.SECONDS))
                    start()
                }
                assertOutput(37)
                main { assertEquals(0, controller.setVolume(65)) }
            }
        } finally {
            main { controller.stop(); controller.release() }
            source.delete()
        }
    }

    @Test fun nativePlaybackPauseSeekDrainAndFailureRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = waveFile("noise-output-test.wav")
        val vlc = LibVLC(context, arrayListOf("--no-video"))
        val player = MediaPlayer(vlc)
        val failure = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val output = NoiseOutput.create(context) { failure.countDown() }
        try {
            output.install(player)
            player.setEventListener { if (it.type == MediaPlayer.Event.EndReached) ended.countDown() }
            Media(vlc, Uri.fromFile(source)).also { player.media = it; it.release() }
            player.play()
            await { output.processedSamples >= 48000 * 3 }
            assertEquals("Processing failed", 1L, failure.count)
            player.pause()
            SystemClock.sleep(150)
            val pausedSamples = output.processedSamples
            SystemClock.sleep(250)
            assertEquals("Pause continued processing", pausedSamples, output.processedSamples)
            player.play()
            await { output.processedSamples > pausedSamples + 48000 }
            player.setTime(2000)
            player.volume = 35
            val resumed = output.processedSamples
            await { output.processedSamples > resumed + 48000 * 20 }
            assertEquals("Processing failed during sustained playback", 1L, failure.count)
            // A startup/seek underrun is possible; sustained callback processing must keep up.
            assertTrue("Sustained output underruns: ${output.underruns}", output.underruns <= 3)
            Log.i("NoiseOutputTest", "Processed ${output.processedSamples} samples; underruns=${output.underruns}")
            player.setTime(34000)
            assertTrue("Drain did not reach end", ended.await(8, TimeUnit.SECONDS))
            output.onControl(4)
            assertTrue("Failure was not reported", failure.await(5, TimeUnit.SECONDS))
            output.cancel()
            player.stop()
            output.close()
            val processed = output.processedSamples
            // Late callbacks after teardown must be inert.
            output.onAudio(ByteBuffer.allocateDirect(960), 0)
            output.onControl(1)
            assertEquals(processed, output.processedSamples)
            val normal = MediaPlayer(vlc)
            try {
                Media(vlc, Uri.fromFile(source)).also { normal.media = it; it.release() }
                normal.play()
                await { normal.time > 500 }
                assertTrue(normal.isPlaying)
            } finally { normal.stop(); normal.release() }
        } finally {
            output.cancel()
            player.stop()
            output.close()
            player.release()
            vlc.release()
            source.delete()
        }
    }

    @Test fun internalRestartsPreservePositionAndUserTransitionsResetFiltering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppContextProvider.init(context)
        val source = waveFile("noise-controller-test.wav")
        lateinit var controller: PlayerController
        val resets = AtomicInteger()
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        val listener = object : MediaPlayerEventListener {
            override suspend fun onEvent(event: MediaPlayer.Event) {}
        }
        fun start() = runBlocking {
            withContext(Dispatchers.Main) {
                val media = Media(VLCInstance.getInstance(context), Uri.fromFile(source))
                try { controller.startPlayback(media, listener, 0) } finally { media.release() }
            }
        }
        main {
            controller = PlayerController(context)
            controller.onNoiseReset = { resets.incrementAndGet() }
        }
        lateinit var output: NoiseOutput
        try {
            start()
            await { controller.mediaplayer.time > 2000 }
            val initialResets = resets.get()
            val position = controller.mediaplayer.time
            output = NoiseOutput.create(context) { fail("Unexpected filter failure") }
            main { assertTrue(controller.changeNoiseOutput(output)) }
            await { output.processedSamples > 48000 && controller.mediaplayer.time >= position }
            assertEquals("Internal restart reset filtering", initialResets, resets.get())
            main { controller.pause() }
            await { controller.isPaused() }
            main { controller.setTime(5000); controller.play() }
            await { controller.isPlaying() && controller.mediaplayer.time > 5500 }
            assertEquals(initialResets, resets.get())
            main { assertTrue(controller.changeNoiseOutput(null)) }
            assertEquals(initialResets, resets.get())
            await { controller.mediaplayer.time > 6000 }
            output = NoiseOutput.create(context) { fail("Unexpected filter failure") }
            main { assertTrue(controller.changeNoiseOutput(output)) }
            await { output.processedSamples > 48000 }
            start() // Replacement, including replacement with the same URI, must reset.
            assertTrue(resets.get() > initialResets)
            val oldSamples = output.processedSamples
            output.onAudio(ByteBuffer.allocateDirect(960), 0)
            assertEquals(oldSamples, output.processedSamples)
            output = NoiseOutput.create(context) { fail("Unexpected filter failure") }
            await { controller.mediaplayer.isSeekable }
            main { assertTrue(controller.changeNoiseOutput(output)); controller.setTime(34000) }
            val beforeEnd = resets.get()
            await { resets.get() > beforeEnd }
            val endedSamples = output.processedSamples
            start() // Repeat-one starts with ordinary output.
            await { controller.mediaplayer.time > 500 }
            assertEquals(endedSamples, output.processedSamples)
            main { controller.stop() }
            assertFalse(controller.isPlaying())
        } finally {
            main { controller.stop(); controller.release() }
            source.delete()
        }
    }

    @Test fun bundledModelReducesNoiseEnergy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loaded = CountDownLatch(1)
        val filter = NativeDeepFilterNet(context)
        filter.onModelLoaded { loaded.countDown() }
        try {
            assertTrue("Offline model did not load", loaded.await(30, TimeUnit.SECONDS))
            val frame = ByteBuffer.allocateDirect(filter.frameLength.toInt()).order(ByteOrder.nativeOrder())
            val random = Random(42)
            var inputEnergy = 0.0
            var outputEnergy = 0.0
            repeat(300) { index ->
                frame.clear()
                repeat(frame.capacity() / 2) {
                    val sample = random.nextInt(16001) - 8000
                    frame.putShort(sample.toShort())
                    if (index >= 100) inputEnergy += sample.toDouble() * sample
                }
                frame.rewind()
                assertTrue(filter.processFrame(frame).isFinite())
                if (index >= 100) for (offset in 0 until frame.capacity() step 2) {
                    val sample = frame.getShort(offset).toDouble()
                    outputEnergy += sample * sample
                }
            }
            Log.i("NoiseOutputTest", "Noise energy ratio=${outputEnergy / inputEnergy}")
            assertTrue("Model did not attenuate noise", outputEnergy < inputEnergy * 0.5)
        } finally { filter.release() }
    }

    private fun waveFile(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, name)
        val pcm = ByteBuffer.allocate(48000 * 35 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(pcm.capacity() / 2) { pcm.putShort((sin(it * 2 * Math.PI * 440 / 48000) * 6000).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.capacity())
        source.outputStream().use { it.write(header.array()); it.write(pcm.array()) }
        return source
    }

    private fun await(timeout: Long = 30000, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Playback progress timed out", ready())
    }
}
