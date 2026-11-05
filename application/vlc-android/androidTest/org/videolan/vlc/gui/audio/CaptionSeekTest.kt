package org.videolan.vlc.gui.audio

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.Keep
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.resources.AppContextProvider
import org.videolan.resources.VLCInstance
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.vlc.discourse.CaptionCue
import org.videolan.vlc.discourse.activeCaption
import org.videolan.vlc.discourse.configureDiscourseInput
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.concurrent.thread

@Keep
@RunWith(AndroidJUnit4::class)
class CaptionSeekTest {
    private lateinit var player: MediaPlayer
    private val generation = AtomicLong()
    private val observed = AtomicReference<Int?>()
    private external fun attach(player: Long): Long
    private external fun detach(handle: Long)
    private external fun clock(): Long

    @Keep fun onAudio(buffer: ByteBuffer, pts: Long) {
        val epoch = generation.get()
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (generation.get() == epoch && clock() < pts - 30000 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(2)
        if (generation.get() != epoch || clock() < pts - 30000) return
        val samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val size = samples.remaining()
        if (size < 1000) return
        var crossings = 0
        var previous = samples.get().toInt()
        while (samples.hasRemaining()) {
            val current = samples.get().toInt()
            if (previous < 0 && current >= 0) crossings++
            previous = current
        }
        val frequency = crossings * 48000.0 / size
        val cue = ((frequency - 300) / 100).roundToInt()
        // Querying the player from its own audio callback can deadlock a paused seek.
        observed.set(cue)
    }
    @Keep fun onControl(action: Int) { if (action == 2) { generation.incrementAndGet(); observed.set(null) } }
    @Keep fun onGain(volume: Float, muted: Boolean) {}

    @Test fun downloadedTrackKeepsCaptionsAlignedAfterForwardAndBackwardSeeks() = exercise(false)

    @Test fun streamedTrackKeepsCaptionsAlignedAfterForwardAndBackwardSeeks() = exercise(true)

    private fun exercise(stream: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AppContextProvider.init(context)
        val source = File(context.cacheDir, "caption-seek.audio")
        instrumentation.context.assets.open("clips/caption-seek-vbr.mp3").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        System.loadLibrary("discourse_audio")
        System.loadLibrary("caption_seek_probe")
        val server = if (stream) RangeServer(source.readBytes()) else null
        player = MediaPlayer(VLCInstance.getInstance(context))
        val handle = attach(player.instance)
        assertTrue(handle != 0L)
        try {
            val media = Media(VLCInstance.getInstance(context), server?.uri ?: Uri.fromFile(source))
            // Legacy queue tags and downloaded .audio URIs must also use accurate seeking.
            val wrapper = MLServiceLocator.getAbstractMediaWrapper(Uri.fromFile(source)).apply {
                tag = "osho_discourse:probe-discourse|probe-audio"
            }
            wrapper.configureDiscourseInput(media)
            player.media = media
            media.release()
            player.play()
            await { player.time > 500 }
            val cues = (0 until 24).map { CaptionCue(it * 5000L, (it + 1) * 5000L, "Phrase $it") }
            for ((index, target) in listOf(62500L, 82500L, 22500L).withIndex()) {
                if (index > 0) repeat(8) { player.setTime(target - 7000 + it * 1000, true); SystemClock.sleep(40) }
                player.setTime(target, false)
                await { observed.get() != null && kotlin.math.abs(player.time - target) < 1500 }
                SystemClock.sleep(350)
                val time = player.time
                val cue = observed.get()!!
                assertEquals("Caption clock $time disagrees with audible phrase $cue after seek to $target", activeCaption(cues, time), cue)
            }
            player.pause()
            await { !player.isPlaying }
            player.setTime(42500, false)
            SystemClock.sleep(150)
            assertFalse("Seeking while paused resumed playback", player.isPlaying)
            player.play()
            await { observed.get() != null && player.time in 42500..44000 }
            SystemClock.sleep(350)
            val time = player.time
            val cue = observed.get()!!
            assertEquals("Captions drifted after a paused seek", activeCaption(cues, time), cue)
        } finally {
            generation.incrementAndGet()
            player.stop()
            detach(handle)
            player.release()
            server?.close()
            source.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Probe timed out at ${player.time}: ${observed.get()}" }; SystemClock.sleep(20) }
    }

    private class RangeServer(private val bytes: ByteArray) : java.io.Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val uri: Uri = Uri.parse("http://127.0.0.1:${server.localPort}/caption-seek.mp3")
        private val worker = thread(isDaemon = true, name = "caption-seek-http") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    var start = 0
                    var partial = false
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            start = line.substringAfter("bytes=").substringBefore('-').toInt()
                            partial = true
                        }
                    }
                    try {
                        val headers = buildString {
                            append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
                            append("Content-Type: audio/mpeg\r\nAccept-Ranges: bytes\r\n")
                            append("Content-Length: ${bytes.size - start}\r\nConnection: close\r\n")
                            if (partial) append("Content-Range: bytes $start-${bytes.lastIndex}/${bytes.size}\r\n")
                            append("\r\n")
                        }
                        socket.getOutputStream().apply { write(headers.toByteArray()); write(bytes, start, bytes.size - start) }
                    } catch (_: SocketException) { /* Seeking cancels the previous range request. */ }
                }
            } catch (_: SocketException) { /* Server closed after playback stopped. */ }
        }
        override fun close() { server.close(); worker.join(5000) }
    }
}
