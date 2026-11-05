package org.videolan.vlc.media

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.resources.AppContextProvider
import org.videolan.resources.VLCInstance
import org.videolan.vlc.audio.NoiseCancellationLevel
import org.videolan.vlc.audio.NoiseOutput
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class StalledPlaybackTest {
    @Test fun pauseAndResumeRecoverAnUnresponsiveHttpInput() = exerciseStalledInput(pause = true)

    @Test fun stalledInputReconnectsWithoutSkippingTheTrack() = exerciseStalledInput(pause = false)

    @Test fun failedReconnectRetainsTheTrackForRetry() = exerciseStalledInput(pause = false, failReconnect = true)

    @Test fun restoredLocalTrackKeepsPlayingAfterRecovery() = exerciseRestoredLocalTrack(paused = false)

    @Test fun pausedLocalRecoveryResumesWithOnePlay() = exerciseRestoredLocalTrack(paused = true)

    @Test fun restoredLocalTrackKeepsPlayingAfterOutputRestart() = exerciseRestoredLocalTrack(paused = false, outputRestart = true)

    @Test fun downloadedInputPreservesPlayingPositionVolumeAndSpeed() = exerciseDownloadSwitch()

    @Test fun downloadedInputKeepsPausedPlaybackPaused() = exerciseDownloadSwitch(paused = true)

    @Test fun playWhileDownloadedInputOpensCancelsPendingPause() = exerciseDownloadSwitch(paused = true, earlyPlay = true)

    @Test fun invalidOfflineInputRestoresStreamWithoutSkipping() = exerciseDownloadSwitch(invalid = true)

    @Test fun downloadedInputPreservesNoiseCancellation() = exerciseDownloadSwitch(filtered = true)

    private fun exerciseDownloadSwitch(paused: Boolean = false, earlyPlay: Boolean = false, invalid: Boolean = false, filtered: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AppContextProvider.init(context)
        val server = StallingServer()
        val file = File(context.cacheDir, "download-handoff.wav").apply {
            writeBytes(if (invalid) "invalid audio".toByteArray() else waveAudio())
        }
        lateinit var controller: PlayerController
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        val events = Collections.synchronizedList(mutableListOf<Int>())
        val listener = object : MediaPlayerEventListener {
            override suspend fun onEvent(event: MediaPlayer.Event) { events.add(event.type) }
        }
        main { controller = PlayerController(context) }
        try {
            runBlocking {
                withContext(Dispatchers.Main) {
                    val media = Media(VLCInstance.getInstance(context), server.uri)
                    media.addOption(":network-caching=100")
                    try { controller.startPlayback(media, listener, 0) } finally { media.release() }
                }
            }
            await { controller.getCurrentTime() > 500 }
            if (filtered) {
                val output = NoiseOutput.create(context, NoiseCancellationLevel.LOW) { fail("Noise output failed") }
                main { assertTrue(controller.changeNoiseOutput(output)) }
                await { output.processedSamples > 24000 }
            }
            main { controller.setVolume(37); controller.setRate(1.25f, false) }
            if (paused) {
                main { controller.pause() }
                await { controller.isPaused() }
            }
            val old = controller.mediaplayer
            val position = old.time
            val pausesBefore = synchronized(events) { events.count { it == MediaPlayer.Event.Paused } }
            runBlocking {
                withContext(Dispatchers.Main) {
                    val media = Media(VLCInstance.getInstance(context), Uri.fromFile(file))
                    try {
                        assertFalse("A stale handoff must not replace the input", controller.replaceAudioSource(media) { false })
                        assertSame(old, controller.mediaplayer)
                        assertTrue(controller.replaceAudioSource(media) { true })
                        if (earlyPlay) controller.play()
                    } finally { media.release() }
                }
            }
            assertNotSame(old, controller.mediaplayer)
            await {
                val media = controller.mediaplayer.media
                val expected = if (invalid) server.uri else Uri.fromFile(file)
                val matches = try { media?.uri == expected } finally { media?.release() }
                matches && if (paused && !earlyPlay) controller.isPaused() &&
                    synchronized(events) { events.count { it == MediaPlayer.Event.Paused } > pausesBefore }
                else controller.isPlaying() && controller.getCurrentTime() > position
            }
            assertEquals(37, controller.getVolume())
            assertEquals(1.25f, controller.getRate(), 0.01f)
            if (!invalid) server.close()
            if (paused && !earlyPlay) {
                assertTrue("Handoff moved the paused position from $position to ${controller.mediaplayer.time}",
                    kotlin.math.abs(controller.mediaplayer.time - position) < 500)
                val stoppedAt = controller.mediaplayer.time
                SystemClock.sleep(600)
                assertTrue(kotlin.math.abs(controller.mediaplayer.time - stoppedAt) < 100)
                main { controller.play() }
            }
            await { controller.getCurrentTime() > position + 1000 }
            if (filtered) {
                val field = PlayerController::class.java.getDeclaredField("noiseOutput").apply { isAccessible = true }
                val output = field.get(controller) as NoiseOutput
                await { output.processedSamples > 24000 }
                assertEquals(NoiseCancellationLevel.LOW, output.appliedLevel)
            }
            assertFalse(events.contains(MediaPlayer.Event.EncounteredError))
            assertFalse(events.contains(MediaPlayer.Event.EndReached))
        } finally {
            server.close()
            main { controller.stop(); controller.release() }
            file.delete()
        }
    }

    private fun exerciseRestoredLocalTrack(paused: Boolean, outputRestart: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AppContextProvider.init(context)
        val file = File(context.cacheDir, "restored-local-track.wav").apply { writeBytes(waveAudio()) }
        lateinit var controller: PlayerController
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        val listener = object : MediaPlayerEventListener {
            override suspend fun onEvent(event: MediaPlayer.Event) {}
        }
        main { controller = PlayerController(context) }
        try {
            runBlocking {
                withContext(Dispatchers.Main) {
                    val media = Media(VLCInstance.getInstance(context), Uri.fromFile(file))
                    try { controller.startPlayback(media, listener, 0, startPaused = true) } finally { media.release() }
                }
            }
            await { controller.isPaused() }
            main { controller.setVolume(37); controller.play() }
            await { controller.getCurrentTime() > 1000 }
            val position = controller.getCurrentTime()
            main {
                if (outputRestart) controller.changeNoiseOutput(null)
                else {
                    // Trigger the same recovery already covered by the stalled-HTTP tests, with a restored local file.
                    controller.javaClass.getDeclaredMethod("recoverAudioPlayback", Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(controller, paused)
                }
            }
            if (paused) {
                await { controller.isPaused() }
                SystemClock.sleep(1200)
                assertEquals(position, controller.getCurrentTime())
                main { controller.play() }
            }
            await { controller.isPlaying() && controller.getCurrentTime() > position + 1000 }
            assertEquals(37, controller.getVolume())
            main { controller.pause() }
            await { controller.isPaused() }
        } finally {
            main { controller.stop(); controller.release() }
            file.delete()
        }
    }

    private fun exerciseStalledInput(pause: Boolean, failReconnect: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AppContextProvider.init(context)
        val server = StallingServer()
        lateinit var controller: PlayerController
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        val events = Collections.synchronizedList(mutableListOf<Int>())
        val listener = object : MediaPlayerEventListener {
            override suspend fun onEvent(event: MediaPlayer.Event) { events.add(event.type) }
        }
        main { controller = PlayerController(context) }
        try {
            runBlocking {
                withContext(Dispatchers.Main) {
                    val media = Media(VLCInstance.getInstance(context), server.uri)
                    media.addOption(":network-caching=100")
                    try { controller.startPlayback(media, listener, 0) } finally { media.release() }
                }
            }
            assertTrue(server.stalled.await(5, TimeUnit.SECONDS))
            await { controller.getCurrentTime() > 500 }
            var lastTime = controller.getCurrentTime()
            var lastChange = SystemClock.elapsedRealtime()
            await(10000) {
                val time = controller.getCurrentTime()
                if (time != lastTime) { lastTime = time; lastChange = SystemClock.elapsedRealtime() }
                SystemClock.elapsedRealtime() - lastChange > 2000
            }
            assertTrue("Fixture must reproduce the falsely playing state", controller.isPlaying())
            val position = controller.getCurrentTime()
            val oldPlayer = controller.mediaplayer
            server.rejectReconnects.set(failReconnect)
            main { controller.setVolume(37) }
            if (pause) {
                main { assertTrue(controller.pause()) }
                await(6000) { controller.isPaused() }
                assertEquals("Recovery lost the playback position", position, controller.getCurrentTime())
                main { controller.play() }
            } else {
                await(35000) { controller.mediaplayer !== oldPlayer }
                if (failReconnect) {
                    await { controller.isPaused() }
                    assertEquals(position, controller.getCurrentTime())
                    assertFalse("Failed reconnect must not invoke the queue's error/skip handler",
                        events.contains(MediaPlayer.Event.EncounteredError))
                    server.rejectReconnects.set(false)
                    main { controller.play() }
                }
            }
            assertEquals(37, controller.getVolume())
            await { controller.isPlaying() && controller.getCurrentTime() > position + 500 }
            assertFalse("Recovery must not advance the queue", events.contains(MediaPlayer.Event.EndReached))
            main { controller.pause() }
            await { controller.isPaused() }
        } finally {
            server.close()
            main { controller.stop(); controller.release() }
        }
    }

    private fun await(timeout: Long = 8000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(30)
        assertTrue("Playback did not reach the expected state within ${timeout}ms", condition())
    }

    /** Sends valid audio, then keeps the first response open forever without EOF or an error. */
    private class StallingServer : AutoCloseable {
        private val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        private val clients = Collections.synchronizedList(mutableListOf<Socket>())
        private val first = AtomicBoolean(true)
        private val finished = CountDownLatch(1)
        val stalled = CountDownLatch(1)
        val rejectReconnects = AtomicBoolean(false)
        val uri = Uri.parse("http://127.0.0.1:${socket.localPort}/track.wav")
        private val audio = waveAudio()

        init {
            thread(isDaemon = true) {
                try {
                    while (!socket.isClosed) {
                        val client = socket.accept()
                        clients.add(client)
                        thread(isDaemon = true) {
                            try {
                                client.use {
                                    val reader = it.getInputStream().bufferedReader()
                                    val request = reader.readLine() ?: return@use
                                    val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                                    if (rejectReconnects.get()) {
                                        it.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                        return@use
                                    }
                                    val offset = headers.firstOrNull { h -> h.startsWith("Range:", true) }
                                        ?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                                    val response = if (offset > 0) "206 Partial Content" else "200 OK"
                                    val range = if (offset > 0) "Content-Range: bytes $offset-${audio.lastIndex}/${audio.size}\r\n" else ""
                                    val output = it.getOutputStream()
                                    output.write(("HTTP/1.1 $response\r\nContent-Type: audio/wav\r\n" +
                                        "Content-Length: ${audio.size - offset}\r\nAccept-Ranges: bytes\r\n${range}Connection: close\r\n\r\n").toByteArray())
                                    if (request.startsWith("HEAD")) return@use
                                    if (offset == 0 && first.compareAndSet(true, false)) {
                                        output.write(audio, 0, 384000 + 44)
                                        output.flush()
                                        stalled.countDown()
                                        finished.await()
                                    } else output.write(audio, offset, audio.size - offset)
                                }
                            } catch (_: Exception) { /* Client cancellation closes the connection. */ }
                        }
                    }
                } catch (_: Exception) { /* Test cleanup closes the listening socket. */ }
            }
        }

        override fun close() {
            finished.countDown()
            socket.close()
            synchronized(clients) { clients.forEach { it.close() } }
        }
    }
}

private fun waveAudio() = ByteBuffer.allocate(44 + 48000 * 2 * 60).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
    putInt(16); putShort(1); putShort(1); putInt(48000); putInt(96000); putShort(2); putShort(16)
    put("data".toByteArray()); putInt(capacity() - 44)
    repeat(48000 * 60) { putShort((sin(it * 2 * Math.PI * 440 / 48000) * 3000).toInt().toShort()) }
}.array()
