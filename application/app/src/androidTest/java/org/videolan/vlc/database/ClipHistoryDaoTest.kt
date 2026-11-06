package org.videolan.vlc.database

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.videolan.vlc.mediadb.models.ClipHistory

class ClipHistoryDaoTest : DbTest() {
    @Test
    fun migrationPreservesExistingDataAndHistorySurvivesReopening() = runBlocking {
        val context = androidx.test.InstrumentationRegistry.getTargetContext()
        val name = "clip-history-migration-test"
        context.deleteDatabase(name)
        fun open() = androidx.room.Room.databaseBuilder(context, MediaDatabase::class.java, name)
            .addMigrations(migration_37_38).build()
        try {
            open().use { database ->
                database.customDirectoryDao().insert(org.videolan.vlc.mediadb.models.CustomDirectory("/clip-history-test"))
                database.openHelper.writableDatabase.apply {
                    execSQL("DROP TABLE clip_history")
                    version = 37
                }
            }
            val entry = ClipHistory("track-a", 1_123, 5_456, 100)
            open().use { database ->
                assertEquals("/clip-history-test", database.customDirectoryDao().get("/clip-history-test").single().path)
                database.clipHistoryDao().save(entry)
            }
            open().use { database ->
                assertEquals(listOf(entry), database.clipHistoryDao().getForAudio("track-a"))
            }
        } finally {
            context.deleteDatabase(name)
        }
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
