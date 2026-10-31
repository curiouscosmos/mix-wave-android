package org.videolan.vlc.discourse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscourseDownloadsTest {
    @Test
    fun partiallyWrittenFilesAreNeverAvailableForClippingOrPlayback() {
        assertEquals(false, isCompletedDownload(100, false, 200))
        assertEquals(false, isCompletedDownload(100, false, null))
        assertEquals(false, isCompletedDownload(100, true, 200))
        assertEquals(false, isCompletedDownload(0, true, null))
        assertEquals(true, isCompletedDownload(200, true, 200))
        assertEquals(true, isCompletedDownload(200, true, -1))
    }

    @Test
    fun usesDownloadManagerTotalWhenAvailable() {
        assertEquals(25, downloadPercent(250L, 1_000L, 2_000L))
    }

    @Test
    fun usesApiFileSizeWhenDownloadManagerTotalIsUnavailable() {
        assertEquals(25, downloadPercent(500L, -1L, 2_000L))
    }

    @Test
    fun hidesProgressWhenNeitherTotalIsAvailable() {
        assertNull(downloadPercent(500L, null, null))
        assertNull(downloadPercent(500L, 0L, 0L))
    }

    @Test
    fun eachTrackCalculatesItsOwnProgress() {
        assertEquals(10, downloadPercent(100L, null, 1_000L))
        assertEquals(75, downloadPercent(750L, null, 1_000L))
    }

    @Test
    fun activeProgressDoesNotReachCompletedState() {
        assertEquals(99, downloadPercent(2_000L, null, 1_000L))
    }
}
