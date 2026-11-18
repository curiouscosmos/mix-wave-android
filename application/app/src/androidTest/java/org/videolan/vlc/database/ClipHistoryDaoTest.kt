package org.videolan.vlc.database

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.videolan.vlc.mediadb.models.ClipHistory
import org.videolan.vlc.mediadb.models.ClipTrackCount
import org.videolan.vlc.mediadb.models.ListenedTrack
import org.videolan.vlc.mediadb.models.ListeningCounts
import org.videolan.vlc.mediadb.models.ListeningDay

class ClipHistoryDaoTest : DbTest() {
    private inline fun MediaDatabase.useDatabase(block: (MediaDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    @Test
    fun migrationPreservesExistingDataAndHistorySurvivesReopening() = runBlocking {
        val context = androidx.test.InstrumentationRegistry.getTargetContext()
        val name = "clip-history-migration-test"
        context.deleteDatabase(name)
        fun open() = androidx.room.Room.databaseBuilder(context, MediaDatabase::class.java, name)
            .addMigrations(migration_37_38, migration_38_39).build()
        try {
            open().useDatabase { database ->
                database.customDirectoryDao().insert(org.videolan.vlc.mediadb.models.CustomDirectory("/clip-history-test"))
                database.openHelper.writableDatabase.apply {
                    execSQL("DROP TABLE clip_history")
                    execSQL("DROP TABLE listening_day")
                    execSQL("DROP TABLE listened_track")
                    version = 37
                }
            }
            val entry = ClipHistory("track-a", 1_123, 5_456, 100)
            open().useDatabase { database ->
                assertEquals("/clip-history-test", database.customDirectoryDao().get("/clip-history-test").single().path)
                database.clipHistoryDao().save(entry)
            }
            open().useDatabase { database ->
                assertEquals(listOf(entry), database.clipHistoryDao().getForAudio("track-a"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun legacyClipRangesAndMetadataSurviveTheStatsMigration() = runBlocking {
        val context = androidx.test.InstrumentationRegistry.getTargetContext()
        val name = "listening-stats-migration-test"
        context.deleteDatabase(name)
        fun open() = androidx.room.Room.databaseBuilder(context, MediaDatabase::class.java, name)
            .addMigrations(migration_38_39).build()
        try {
            open().useDatabase { database ->
                database.openHelper.writableDatabase.apply {
                    execSQL("DROP TABLE clip_history")
                    execSQL("DROP TABLE listening_day")
                    execSQL("DROP TABLE listened_track")
                    migration_37_38.migrate(this)
                    execSQL("INSERT INTO clip_history VALUES ('legacy', 1000, 3000, 100)")
                    version = 38
                }
            }
            open().useDatabase { database ->
                val clips = database.clipHistoryDao()
                val legacy = ClipHistory("legacy", 1000, 3000, 100)
                assertEquals(listOf(legacy), clips.getForAudio("legacy"))
                assertEquals(-1L, clips.save(legacy.copy(createdAt = 200)))
                clips.fillMetadata("legacy", "Track", "series", "Series")
                assertEquals(listOf(legacy.copy(trackTitle = "Track", discourseId = "series", discourseTitle = "Series")),
                    clips.getForAudio("legacy"))
                assertEquals(listOf(ClipTrackCount("legacy", "Track", "series", "Series", 1)),
                    clips.countsByTrack().first())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun aggregatesDurationsAndDistinctRepeatsAndClearsBoth() = runBlocking {
        val dao = db.listeningStatsDao()
        repeat(2) { dao.record(listOf(ListeningDay(5, 1200)), ListenedTrack("track", "discourse")) }
        dao.record(emptyList(), ListenedTrack("another", "discourse"))
        dao.record(emptyList(), ListenedTrack("local", null))
        assertEquals(listOf(ListeningDay(5, 2400)), dao.days().first())
        assertEquals(ListeningCounts(3, 1, 1, 1), dao.counts().first())
        dao.clear()
        assertEquals(emptyList<ListeningDay>(), dao.days().first())
        assertEquals(ListeningCounts(0, 0, 0, 0), dao.counts().first())
    }

    @Test
    fun rangesAreUniqueOrderedAndIsolatedByTrack() = runBlocking {
        val dao = db.clipHistoryDao()
        val first = ClipHistory("track-a", 1_123, 5_456, 100)
        val second = ClipHistory("track-a", 2_000, 6_000, 200)
        dao.save(first)
        dao.save(second)
        dao.save(first.copy(createdAt = 300))
        val other = first.copy(audioId = "track-b", createdAt = 400)
        dao.save(other)
        assertEquals(listOf(second, first), dao.getForAudio("track-a"))
        assertEquals(listOf(other), dao.getForAudio("track-b"))
        assertEquals(emptyList<ClipHistory>(), dao.getForAudio("missing"))
    }
}
