package org.videolan.vlc

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.toMediaWrapper
import org.videolan.vlc.discourse.DiscourseRepository
import org.videolan.vlc.discourse.DiscourseStatsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@RunWith(AndroidJUnit4::class)
class QueueHistoryTest {
    @Test fun restoredQueueRecordsAutomaticallyStartedDiscourse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = org.videolan.tools.Settings.getInstance(context)
        val recentKey = "osho_api_recently_played_audios"
        val previousRecent = settings.getString(recentKey, null)
        val stats = context.getSharedPreferences("osho_api_stats", android.content.Context.MODE_PRIVATE)
        val previousStats = listOf("discourses", "audios").associateWith { stats.getStringSet(it, emptySet())?.toSet() }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(message: String = "Queue transition timed out", ready: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertTrue(message, ready())
        }
        fun wave(name: String, seconds: Int): File {
            val file = File(context.cacheDir, name)
            val size = 48000 * seconds * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size)
            file.outputStream().use { it.write(header.array()); it.write(ByteArray(size)) }
            return file
        }
        val first = wave("history-first.wav", 2)
        val second = wave("history-second.wav", 70)
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        context.startForegroundService(Intent(context, PlaybackService::class.java))
        await { PlaybackService.instance != null }
        val service = PlaybackService.instance!!
        val queue = listOf(first, second).map {
            val audio = DiscourseAudio(it.name, "history-test", "History test", null, "english", it.name,
                "https://example.test/${it.name}", 70.0, null, null, 1, "", "")
            MLServiceLocator.getAbstractMediaWrapper(Uri.fromFile(it)).apply {
                type = MediaWrapper.TYPE_AUDIO
                tag = audio.toMediaWrapper(context).tag
            }
        }
        DiscourseStatsStore(context).markSent("history-test", second.name)
        try {
            main { service.load(queue, 0) }
            await { service.currentMediaWrapper === queue[0] && service.isPlaying && service.getTime() > 100 }
            runBlocking { withContext(Dispatchers.Main) { service.playlistManager.saveMediaList() } }
            main { service.stop(); assertTrue(service.playlistManager.loadLastPlaylist()) }
            await { service.currentMediaWrapper?.uri == queue[1].uri && service.isPlaying && service.getTime() > 500 }
            assertEquals("Restored next track lost discourse metadata", queue[1].tag, service.currentMediaWrapper?.tag)
            main {
                val timer = PlaybackService::class.java.getDeclaredField("playbackHistoryTimer").apply { isAccessible = true }.get(service)
                val track = timer.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(timer)
                assertSame("Next track has no history timer", service.currentMediaWrapper, track)
                val job = PlaybackService::class.java.getDeclaredField("playbackHistoryJob").apply { isAccessible = true }.get(service) as? kotlinx.coroutines.Job
                assertTrue("Next track history timer was cancelled", job?.isActive == true)
                // Advance only the listening clock; still run the service's real qualification and history write.
                timer.javaClass.getDeclaredField("startedAt").apply { isAccessible = true }
                    .set(timer, SystemClock.elapsedRealtime() - 60001L)
                service.pause()
            }
            await("Automatically started discourse was not recorded in Recently Played") {
                DiscourseRepository(context).recentlyPlayedAudios.any { it.id == second.name }
            }
        } finally {
            main { service.stop() }
            settings.edit().putString(recentKey, previousRecent).apply()
            stats.edit().also { editor -> previousStats.forEach { (key, value) -> editor.putStringSet(key, value) } }.apply()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            first.delete()
            second.delete()
        }
    }
}
