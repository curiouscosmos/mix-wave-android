package org.videolan.vlc.discourse

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.videolan.tools.Settings

class DiscourseLikesRepositoryTest {
    private lateinit var settings: SharedPreferences
    private lateinit var api: DiscourseApi
    private lateinit var repository: DiscourseRepository

    @Before
    fun setup() {
        val values = HashMap<String, Any>()
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String>(); editor }
        every { editor.putStringSet(any(), any()) } answers { values[firstArg()] = secondArg<Set<String>>().toSet(); editor }
        every { editor.apply() } returns Unit
        settings = mockk {
            every { edit() } returns editor
            every { contains(any()) } answers { values.containsKey(firstArg<String>()) }
            every { getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
            every { getStringSet(any(), any()) } answers {
                @Suppress("UNCHECKED_CAST")
                (values[firstArg()] as? Set<String>) ?: secondArg<Set<String>>()
            }
        }
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns settings
        mockkObject(Settings, OshoUserIdentity)
        every { Settings.getInstance(context) } returns settings
        every { OshoUserIdentity.ensure(context) } returns "user"
        settings.edit().putStringSet(DiscourseLikesStore.DISCOURSES, setOf("parent"))
            .putStringSet(DiscourseLikesStore.AUDIOS, setOf("audio")).apply()
        api = mockk()
        repository = DiscourseRepository(context, api)
        val parent = Discourse(id = "parent", title = "Parent", thumbnailUrl = null, isAudioCleaned = false,
            language = "hindi", slug = null, createdAt = "", updatedAt = "", totalLikes = 5)
        repository.recordRecentlyPlayed(parent)
        repository.recordRecentlyPlayed(parent.copy(id = "other"))
        val audio = DiscourseAudio(id = "audio", discourseId = "parent", discourseName = "Parent",
            discourseThumbnailUrl = null, language = "hindi", title = "Audio", audioUrl = "https://example.test/audio",
            durationSeconds = null, fileSize = null, mimeType = null, trackNumber = null, createdAt = "",
            updatedAt = "", totalLikes = 1)
        repository.recordRecentlyPlayed(audio)
        repository.recordRecentlyPlayed(audio.copy(id = "other-audio"))
    }

    @After
    fun cleanup() = unmockkObject(Settings, OshoUserIdentity)

    @Test
    fun audioTogglesOnlyChangeAudioCountsAndPreserveDiscourseStateAndHistory() = runBlocking {
        coEvery { api.unlike("user", null, "audio") } returns UnlikeResponse(
            UnlikeData(discourseAudioId = "audio", likedByUserId = "user", unliked = true,
                totalLikes = 0))
        repository.toggleDiscourseAudioLike("audio", 1)
        coVerify(exactly = 1) { api.unlike("user", null, "audio") }
        assertEquals(listOf("other-audio", "audio"), repository.recentlyPlayedAudios.map { it.id })
        assertEquals(0, repository.recentlyPlayedAudios.last().totalLikes)
        assertEquals(listOf("other", "parent"), repository.recentlyPlayedDiscourses.map { it.id })
        assertEquals(5, repository.recentlyPlayedDiscourses.last().totalLikes)
        assertTrue("parent" in repository.likedDiscourses)
        assertFalse("audio" in repository.likedAudios)
        repository.recordRecentlyPlayed(repository.recentlyPlayedAudios.last().copy(totalLikes = 1))
        repository.recordRecentlyPlayed(repository.recentlyPlayedDiscourses.last().copy(totalLikes = 5))
        assertEquals(0, repository.recentlyPlayedAudios.first().totalLikes)
        assertEquals(5, repository.recentlyPlayedDiscourses.first().totalLikes)
        coEvery { api.likeDiscourseAudio("audio", LikeRequest("user")) } returns LikeResponse(
            LikeData(discourseAudioId = "audio", likedByUserId = "user", liked = true, totalLikes = 1))
        repository.toggleDiscourseAudioLike("audio", 0)
        assertEquals(1, repository.recentlyPlayedAudios.first().totalLikes)
        assertEquals(5, repository.recentlyPlayedDiscourses.first().totalLikes)
        assertTrue("parent" in repository.likedDiscourses)
    }

    @Test
    fun discourseUnlikeIsIdempotentAndToggleCanLikeAgain() = runBlocking {
        coEvery { api.unlike("user", "parent", null) } returns UnlikeResponse(
            UnlikeData(discourseId = "parent", likedByUserId = "user", unliked = false, totalLikes = 4))
        coEvery { api.likeDiscourse("parent", LikeRequest("user")) } returns LikeResponse(
            LikeData(discourseId = "parent", likedByUserId = "user", liked = true, totalLikes = 5))
        repository.toggleDiscourseLike("parent", 5)
        assertFalse("parent" in repository.likedDiscourses)
        assertEquals(4, repository.recentlyPlayedDiscourses.last().totalLikes)
        repository.toggleDiscourseLike("parent", 4)
        assertTrue("parent" in repository.likedDiscourses)
        assertEquals(5, repository.recentlyPlayedDiscourses.last().totalLikes)
        coVerify(exactly = 1) { api.unlike("user", "parent", null) }
        coVerify(exactly = 1) { api.likeDiscourse("parent", LikeRequest("user")) }
    }
}
