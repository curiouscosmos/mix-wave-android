package org.videolan.vlc.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One bounded frame; VLC callback sizes need not match the model's frame size. */
internal class PcmFrames(val size: Int) {
    init { require(size > 0 && size % 2 == 0) }
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    fun append(source: ByteBuffer, emit: (ByteBuffer, Int) -> Unit) {
        while (source.hasRemaining()) {
            val count = minOf(source.remaining(), buffer.remaining())
            val limit = source.limit()
            source.limit(source.position() + count)
            buffer.put(source)
            source.limit(limit)
            if (!buffer.hasRemaining()) {
                buffer.flip()
                emit(buffer, size)
                buffer.clear()
            }
        }
    }

    fun drain(emit: (ByteBuffer, Int) -> Unit) {
        val count = buffer.position()
        if (count == 0) return
        while (buffer.hasRemaining()) buffer.put(0)
        buffer.flip()
        emit(buffer, count)
        buffer.clear()
    }
}
