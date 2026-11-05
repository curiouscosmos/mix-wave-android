package org.videolan.vlc.discourse

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test
import okhttp3.Request
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

class DiscourseCaptionsTest {
    @Test fun separateDiskCacheSurvivesClientRecreationAndOfflineNetworkFailure() {
        val directory = Files.createTempDirectory("captions-cache").toFile()
        val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}/captions.srt"
        var headers = ""
        val worker = thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                headers = buildString {
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; appendLine(line) }
                }
                val body = "1\n00:00:01,000 --> 00:00:02,000\nध्यान"
                val bytes = body.toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
            }
        }
        var client = captionClient(directory)
        try {
            val request = Request.Builder().url(url).build()
            val expected = client.newCall(request).execute().use { it.body!!.string() }
            worker.join(5000)
            server.close()
            assertFalse(headers.contains("x-api-key", ignoreCase = true))
            assertEquals(10L * 1024 * 1024, client.cache!!.maxSize())
            client.cache!!.close()
            client = captionClient(directory)
            val offline = request.newBuilder().header("Cache-Control", "no-cache").build()
            client.newCall(offline).execute().use {
                assertEquals(200, it.code)
                assertNotNull(it.cacheResponse)
                assertEquals(expected, it.body!!.string())
            }
        } finally {
            server.close()
            client.cache!!.close()
            directory.deleteRecursively()
        }
    }

    @Test fun failedFetchCanBeRetriedWithoutCachingTheFailure() {
        val directory = Files.createTempDirectory("captions-retry").toFile()
        ServerSocket(0).use { server ->
            val worker = thread {
                for (code in listOf(503, 200)) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 $code Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
            val client = captionClient(directory)
            try {
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/captions").build()
                client.newCall(request).execute().use { assertEquals(503, it.code) }
                client.newCall(request).execute().use { assertEquals(200, it.code) }
                worker.join(5000)
            } finally { client.cache!!.close(); directory.deleteRecursively() }
        }
    }
    @Test fun highlightingUsesExclusiveEndsAndClearsDuringGapsAndBackwardSeeks() {
        val cues = listOf(CaptionCue(1000, 2000, "first"), CaptionCue(3000, 4000, "second"))
        assertEquals(-1, activeCaption(cues, 999))
        assertEquals(0, activeCaption(cues, 1000))
        assertEquals(0, activeCaption(cues, 1999))
        assertEquals(-1, activeCaption(cues, 2000))
        assertEquals(1, activeCaption(cues, 3500))
        assertEquals(0, activeCaption(cues, 1500))
        assertEquals(-1, activeCaption(cues, 4000))
        assertEquals(-1, activeCaption(emptyList(), 1000))
    }
    @Test fun srtPreservesWholeHindiPhrasesAndLineBreaks() {
        val cues = parseCaptions("\uFEFF1\r\n00:00:01,250 --> 00:00:03,500\r\nप्रेम, ध्यान।\r\nदूसरी पंक्ति!\r\n\r\n2\r\n00:00:05,000 --> 00:00:06,000\r\nअगला")
        assertEquals(2, cues.size)
        assertEquals(CaptionCue(1250, 3500, "प्रेम, ध्यान।\nदूसरी पंक्ति!"), cues[0])
    }

    @Test fun vttAcceptsIdentifiersSettingsAndSeparatelyTimedWords() {
        val cues = parseCaptions("WEBVTT\n\nNOTE ignore\nnot a cue\n\nphrase\n00:01.000 --> 00:02.000 align:start\nOne\n\n00:02.000 --> 00:03.500\nTwo")
        assertEquals(listOf(CaptionCue(1000, 2000, "One"), CaptionCue(2000, 3500, "Two")), cues)
    }

    @Test fun malformedAndEmptyCuesAreIgnored() {
        assertTrue(parseCaptions("not captions").isEmpty())
        assertTrue(parseCaptions("1\n00:00:03,000 --> 00:00:02,000\nbackwards\n\n00:99:00.000 --> 00:99:01.000\nbad").isEmpty())
    }

    @Test fun phraseBoundariesPreserveOppositeBracket() {
        val original = ClipRange(1000, 10000)
        val cue = CaptionCue(3000, 4500, "a complete phrase")
        repeat(3) {
            assertEquals(ClipRange(3000, 10000), captionRange(original, cue, true, 20000))
            assertEquals(ClipRange(1000, 4500), captionRange(original, cue, false, 20000))
        }
        assertNull(captionRange(original, CaptionCue(9500, 11000, "cross"), true, 20000))
        assertNull(captionRange(original, CaptionCue(0, 1500, "short"), false, 20000))
        assertEquals(ClipRange(9000, 10000), captionRange(original, CaptionCue(9000, 10000, "one second"), true, 20000))
    }

    @Test fun captionUrlsEncodeSpacesWithoutDoubleEncoding() {
        assertEquals("https://example.org/Hindi%20Captions/file%201.srt", captionUrl("https://example.org/Hindi Captions/file%201.srt").toString())
        assertEquals("https://osho.b-cdn.net/OSHO/Hindi%20Captions/file.srt", captionUrl("/Hindi Captions/file.srt").toString())
        assertNull(captionUrl("http://example.org/captions.srt"))
        assertNull(captionUrl("file:///tmp/captions.srt"))
    }

    @Test fun oldAndNullableSnapshotsRemainReadableAndCaptionFieldsRoundTrip() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(DiscourseAudio::class.java)
        val json = """{"id":"a","discourse_id":"d","discourse_name":"Discourse","discourse_thumbnail_url":null,"language":"hindi","title":"Track","audio_url":"/audio.mp3","duration_seconds":null,"file_size":null,"mime_type":null,"track_number":null,"created_at":"","updated_at":""}"""
        val old = adapter.fromJson(json)!!
        assertEquals(false, old.hasCc)
        assertNull(old.ccPath)
        val updated = old.copy(hasCc = true, ccPath = "/Hindi captions.srt")
        assertEquals(updated, adapter.fromJson(adapter.toJson(updated)))
        assertNull(adapter.fromJson(json.dropLast(1) + ",\"has_cc\":null,\"cc_path\":null}")!!.hasCc)
    }

    @Test fun longTranscriptsKeepEveryExplicitSegment() {
        val source = (0 until 1428).joinToString("\n\n") { index ->
            fun time(seconds: Int) = "%02d:%02d:%02d,000".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            "$index\n${time(index * 2)} --> ${time(index * 2 + 1)}\nवाक्य $index"
        }
        assertEquals(1428, parseCaptions(source).size)
    }
}
