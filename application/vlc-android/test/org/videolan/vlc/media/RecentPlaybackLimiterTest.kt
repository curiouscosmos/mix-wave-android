package org.videolan.vlc.media

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPlaybackLimiterTest {
    @Test
    fun interleavesBothSourcesUntilTheLimit() {
        val discourse = (1..20).map { "discourse-$it" }
        val local = (1..20).map { "local-$it" }

        val result = interleaveRecent(discourse, local)

        assertEquals(MAX_RECENT_PLAYED, result.size)
        assertEquals(listOf("discourse-1", "local-1", "discourse-2", "local-2"), result.take(4))
        assertEquals(listOf("discourse-7", "local-7", "discourse-8", "local-8"), result.takeLast(4))
    }

    @Test
    fun appendsRemainingEntriesWhenOneSourceIsShort() {
        val discourse = listOf("d1", "d2", "d3")
        val local = listOf("l1")

        assertEquals(listOf("d1", "l1", "d2", "d3"), interleaveRecent(discourse, local))
        assertEquals(listOf("d1", "d2", "d3"), interleaveRecent(discourse, emptyList()))
    }

    @Test
    fun splitsRecentHistoryIntoKeptAndStaleEntries() {
        val history = (1..20).map { "track-$it" }

        val (kept, stale) = splitRecentHistory(history)

        assertEquals(16, kept.size)
        assertEquals(listOf("track-1", "track-16"), listOf(kept.first(), kept.last()))
        assertEquals(listOf("track-17", "track-20"), listOf(stale.first(), stale.last()))
    }
}
