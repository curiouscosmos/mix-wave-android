package org.videolan.vlc.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PcmFramesTest {
    @Test fun callbackBoundariesDoNotLoseOrRepeatSamples() {
        val frames = PcmFrames(8)
        val emitted = ArrayList<Byte>()
        val collect: (ByteBuffer, Int) -> Unit = { buffer, count ->
            assertEquals(0, buffer.position())
            assertEquals(8, buffer.remaining())
            repeat(count) { emitted.add(buffer.get()) }
        }
        val input = ByteArray(54) { it.toByte() }
        for (range in listOf(0 until 2, 2 until 24, 24 until 30, 30 until 54)) {
            frames.append(ByteBuffer.wrap(input.copyOfRange(range.first, range.last + 1)), collect)
        }
        assertEquals(48, emitted.size)
        frames.drain(collect)
        frames.drain(collect)
        assertArrayEquals(input, emitted.toByteArray())
    }

    @Test fun drainPadsModelFrameButEmitsOnlyRealSamples() {
        val frames = PcmFrames(8)
        frames.append(ByteBuffer.wrap(byteArrayOf(1, 2))) { _, _ -> fail("Incomplete frame") }
        frames.drain { buffer, count ->
            assertEquals(2, count)
            val padded = ByteArray(8)
            buffer.get(padded)
            assertArrayEquals(byteArrayOf(1, 2, 0, 0, 0, 0, 0, 0), padded)
        }
        frames.append(ByteBuffer.wrap(ByteArray(8) { 3 })) { buffer, count ->
            assertEquals(8, count)
            assertEquals(3, buffer.get().toInt())
        }
    }

    @Test fun newSessionDoesNotContainPartialAudioFromPreviousSession() {
        val old = PcmFrames(8)
        old.append(ByteBuffer.wrap(byteArrayOf(9, 9))) { _, _ -> fail() }
        val replacement = PcmFrames(8)
        replacement.append(ByteBuffer.wrap(ByteArray(8) { 1 })) { buffer, _ ->
            while (buffer.hasRemaining()) assertEquals(1, buffer.get().toInt())
        }
    }
}
