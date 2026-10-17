package org.videolan.vlc.discourse

import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.verify
import io.mockk.every
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.videolan.tools.ErrorReporter
import org.robolectric.shadows.ShadowToast
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.videolan.tools.Settings
import org.videolan.vlc.BaseTest

@Config(sdk = [28])
class DiscourseRepositoryTest : BaseTest() {
    private lateinit var repository: DiscourseRepository
    private lateinit var api: DiscourseApi

    @Before
    fun clearRecentlyPlayed() {
        Settings.getInstance(context).edit()
            .remove("osho_api_recently_played_discourses")
            .remove("osho_api_recently_played_audios")
            .commit()
        api = mockk()
        repository = DiscourseRepository(context, api)
        mockkObject(ErrorReporter)
        every { ErrorReporter.error(any(), any(), any()) } returns Unit
        ShadowToast.reset()
    }

    @Test
    fun catalogueFailureShowsToastAndPreservesRetryError() = runBlocking {
        val failure = retrofit2.HttpException(retrofit2.Response.error<Any>(500,
            okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), "{\"error\":\"Internal server error\"}")))
        coEvery { api.discourses(any(), any(), any(), any(), any(), any()) } throws failure
        try {
            repository.getDiscourses()
            org.junit.Assert.fail("Expected caller to receive failure for its retry state")
        } catch (error: retrofit2.HttpException) {
            assertSame(failure, error)
        }
        assertEquals("Failed to communicate with the server.", ShadowToast.getTextOfLatestToast())
        verify { ErrorReporter.error(any(), any(), failure) }
    }

    @Test
    fun diagnosticsFailureIsSilentAndReported() = runBlocking {
        val failure = java.io.IOException("Server unavailable")
        coEvery { api.index(any()) } throws failure
        assertEquals(null, repository.apiIndex())
        assertEquals(0, ShadowToast.shownToastCount())
        verify { ErrorReporter.error(any(), any(), failure) }
    }

    @Test
    fun audioFetchFailuresShowToastForBothOverloads() = runBlocking {
        val failure = java.io.IOException("Server unavailable")
        coEvery { api.discourseAudios(any<String>(), any()) } throws failure
        coEvery { api.discourseAudios(any<Int>(), any(), any(), any(), any()) } throws failure
        val requests: List<suspend () -> Any> = listOf(
            { repository.getDiscourseAudios("discourse") },
            { repository.getDiscourseAudios(page = 1) }
        )
        requests.forEach { request ->
            try {
                request()
                org.junit.Assert.fail("Expected fetch failure")
            } catch (error: java.io.IOException) {
                assertSame(failure, error)
            }
        }
        assertEquals(2, ShadowToast.shownToastCount())
        verify(exactly = 2) { ErrorReporter.error(any(), any(), failure) }
    }

    @Test
    fun failedLikeRollsBackAndIsReportedWithoutToast() = runBlocking {
        mockkObject(OshoUserIdentity)
        every { OshoUserIdentity.ensure(any()) } returns "user"
        val failure = java.io.IOException("Server unavailable")
        coEvery { api.likeDiscourse("failed-like", any()) } throws failure
        assertEquals(null, repository.likeDiscourse("failed-like", 5))
        org.junit.Assert.assertFalse("failed-like" in repository.likedDiscourses)
        org.junit.Assert.assertTrue(repository.likes.value.pending.isEmpty())
        assertEquals(0, ShadowToast.shownToastCount())
        verify { ErrorReporter.error(any(), any(), failure) }
    }

    @Test
    fun cancellationIsNeverReportedOrShown() = runBlocking {
        val cancellation = CancellationException("Stopped")
        coEvery { api.index(any()) } throws cancellation
        try {
            repository.apiIndex()
            org.junit.Assert.fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        verify(exactly = 0) { ErrorReporter.error(any(), any(), any()) }
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun storesFullDiscoursesNewestFirstAndCapsAt24() {
        val discourses = (1..25).map { discourse("discourse-$it") }

        discourses.forEach(repository::recordRecentlyPlayed)

        assertEquals(24, repository.recentlyPlayedDiscourses.size)
        assertEquals(discourses.last(), repository.recentlyPlayedDiscourses.first())
        assertEquals(discourses[1], repository.recentlyPlayedDiscourses.last())
    }

    @Test
    fun replayingDiscourseMovesItToTheFrontWithoutDuplication() {
        val first = discourse("first")
        val second = discourse("second")
        repository.recordRecentlyPlayed(first)
        repository.recordRecentlyPlayed(second)

        repository.recordRecentlyPlayed(first)

        assertEquals(listOf(first, second), repository.recentlyPlayedDiscourses)
    }

    @Test
    fun storesFullAudiosNewestFirstAndCapsAt24() {
        val audios = (1..25).map { audio("audio-$it") }

        audios.forEach(repository::recordRecentlyPlayed)

        assertEquals(24, repository.recentlyPlayedAudios.size)
        assertEquals(audios.last(), repository.recentlyPlayedAudios.first())
        assertEquals(audios[1], repository.recentlyPlayedAudios.last())
    }

    @Test
    fun replayingAudioMovesItToTheFrontWithoutDuplication() {
        val first = audio("first")
        val second = audio("second")
        repository.recordRecentlyPlayed(first)
        repository.recordRecentlyPlayed(second)

        repository.recordRecentlyPlayed(first)

        assertEquals(listOf(first, second), repository.recentlyPlayedAudios)
    }

    @Test
    fun queueTransitionsRecordThePlayingTrackWithoutCatalogueOrNetworkCalls() {
        val first = audio("first")
        val second = audio("second").copy(title = "Title | with separator")
        val queue = listOf(first, second).map { it.toMediaWrapper(context) }
        assertEquals(emptyList<DiscourseAudio>(), repository.recentlyPlayedAudios)

        queue[0].recordDiscourseRecentlyPlayed(context)
        queue[1].recordDiscourseRecentlyPlayed(context)

        assertEquals(DiscoursePlaybackIds(second.discourseId, second.id), queue[1].discoursePlaybackIds())
        assertEquals(listOf(second, first), repository.recentlyPlayedAudios)
        queue[0].recordDiscourseRecentlyPlayed(context)
        assertEquals(listOf(first, second), repository.recentlyPlayedAudios)
    }

    private fun discourse(id: String) = Discourse(
        id = id,
        title = "Title $id",
        thumbnailUrl = "https://example.test/$id.jpg",
        isAudioCleaned = true,
        language = "hindi",
        slug = "slug-$id",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-02T00:00:00Z",
        totalTracks = 3,
        totalLikes = 7
    )

    private fun audio(id: String) = DiscourseAudio(
        id = id,
        discourseId = "discourse-$id",
        discourseName = "Discourse $id",
        discourseThumbnailUrl = "https://example.test/$id.jpg",
        language = "hindi",
        title = "Track $id",
        audioUrl = "https://example.test/$id.mp3",
        durationSeconds = 120.0,
        fileSize = 100L,
        mimeType = "audio/mpeg",
        trackNumber = 1,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-02T00:00:00Z",
        totalLikes = 7
    )
}
