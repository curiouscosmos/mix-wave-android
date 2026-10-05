package org.videolan.vlc.discourse

import android.content.SharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class DiscourseLikesStoreTest {
    private lateinit var settings: SharedPreferences
    private lateinit var store: DiscourseLikesStore
    private var now = 1_000_000L

    @Before
    fun setUpStore() {
        val values = HashMap<String, Any>()
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
        every { editor.putStringSet(any(), any()) } answers { values[firstArg()] = secondArg<Set<String>>().toSet(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        every { editor.apply() } returns Unit
        every { editor.commit() } returns true
        settings = mockk {
            every { edit() } returns editor
            every { contains(any()) } answers { values.containsKey(firstArg<String>()) }
            every { getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { getStringSet(any(), any()) } answers {
                @Suppress("UNCHECKED_CAST")
                (values[firstArg()] as? Set<String>) ?: secondArg<Set<String>>()
            }
        }
        now = 1_000_000L
        store = DiscourseLikesStore(settings) { now }
    }

    @Test
    fun parsesTheLikesRouteWithoutADataEnvelope() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(UserLikesResponse::class.java)
        assertEquals(UserLikesResponse(setOf("discourse"), setOf("audio")),
            adapter.fromJson("""{"discourse":["discourse"],"discourse_audio":["audio"]}"""))
        assertEquals(UserLikesResponse(emptySet(), emptySet()),
            adapter.fromJson("""{"discourse":[],"discourse_audio":[]}"""))
    }

    @Test
    fun cachesEmptyArraysAndExpiresAtExactlySevenDays() = runBlocking {
        var requests = 0
        val request: suspend () -> UserLikesResponse = {
            requests++
            UserLikesResponse(emptySet(), emptySet())
        }
        store.refresh(request)
        now += DiscourseLikesStore.TTL - 1
        store.refresh(request)
        assertEquals(1, requests)
        now++
        store.refresh(request)
        assertEquals(2, requests)
    }

    @Test
    fun missingArrayOrTimestampRequiresFetchAndReplacesLegacyLikes() = runBlocking {
        settings.edit().putStringSet(DiscourseLikesStore.DISCOURSES, setOf("legacy"))
            .putStringSet(DiscourseLikesStore.AUDIOS, emptySet()).commit()
        store.refresh { UserLikesResponse(setOf("remote"), setOf("audio")) }
        assertEquals(setOf("remote"), store.state.value.discourseIds)
        assertEquals(setOf("audio"), store.state.value.audioIds)
        settings.edit().remove(DiscourseLikesStore.AUDIOS).commit()
        var fetched = false
        store.refresh {
            fetched = true
            UserLikesResponse(emptySet(), emptySet())
        }
        assertTrue(fetched)
    }

    @Test
    fun refreshFailureDoesNotEraseCacheOrRenewExpiry() = runBlocking {
        store.refresh { UserLikesResponse(setOf("saved"), emptySet()) }
        val fetchedAt = settings.getLong(DiscourseLikesStore.FETCHED_AT, -1)
        now += DiscourseLikesStore.TTL
        try {
            store.refresh { throw IOException("offline") }
            fail("Expected refresh failure")
        } catch (_: IOException) { }
        assertEquals(setOf("saved"), store.state.value.discourseIds)
        assertEquals(fetchedAt, settings.getLong(DiscourseLikesStore.FETCHED_AT, -1))
    }

    @Test
    fun likeIsOptimisticDeduplicatedAndPersistsAuthoritativeCountWithoutRenewingExpiry() = runBlocking {
        store.refresh { UserLikesResponse(emptySet(), emptySet()) }
        val fetchedAt = settings.getLong(DiscourseLikesStore.FETCHED_AT, -1)
        now++
        val response = CompletableDeferred<LikeData>()
        val target = DiscourseLikesStore.Target("audio", true)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            store.like("audio", true, 7) { response.await() }
        }
        assertTrue(store.state.value.liked(target))
        assertEquals(8, store.state.value.counts[target])
        assertEquals(emptySet<String>(), settings.getStringSet(DiscourseLikesStore.AUDIOS, emptySet()))
        assertNull(store.like("audio", true, 7) { error("Duplicate request") })
        response.complete(LikeData(discourseAudioId = "audio", likedByUserId = "user", liked = true, totalLikes = 12))
        pending.await()
        assertEquals(setOf("audio"), settings.getStringSet(DiscourseLikesStore.AUDIOS, emptySet()))
        assertEquals(12, store.state.value.counts[target])
        assertTrue(store.state.value.discourseIds.isEmpty())
        assertTrue(store.state.value.pending.isEmpty())
        assertEquals(fetchedAt, settings.getLong(DiscourseLikesStore.FETCHED_AT, -1))
        assertNull(store.like("audio", true, 7) { error("Already liked") })
    }

    @Test
    fun failedAndCancelledLikesRollBackAndCanBeRetried() = runBlocking {
        val target = DiscourseLikesStore.Target("discourse", false)
        try {
            store.like("discourse", false, 7) { throw IOException("offline") }
            fail("Expected like failure")
        } catch (_: IOException) { }
        assertFalse(store.state.value.liked(target))
        assertNull(store.state.value.counts[target])
        assertFalse(settings.contains(DiscourseLikesStore.FETCHED_AT))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            store.like("discourse", false, 7) { CompletableDeferred<LikeData>().await() }
        }
        assertTrue(store.state.value.liked(target))
        waiting.cancel()
        waiting.join()
        assertFalse(store.state.value.liked(target))
        store.like("discourse", false, 7) {
            LikeData(discourseId = "discourse", likedByUserId = "user", liked = true, totalLikes = 8)
        }
        assertTrue(store.state.value.liked(target))
    }

    @Test
    fun concurrentRefreshesAreDeduplicatedAndCannotOverwritePendingLikes() = runBlocking {
        val response = CompletableDeferred<UserLikesResponse>()
        var requests = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            store.refresh { requests++; response.await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            store.refresh { requests++; error("Duplicate refresh") }
        }
        val like = async(start = CoroutineStart.UNDISPATCHED) {
            store.like("new", false, 3) {
                LikeData(discourseId = "new", likedByUserId = "user", liked = true, totalLikes = 4)
            }
        }
        assertTrue("new" in store.state.value.discourseIds)
        response.complete(UserLikesResponse(setOf("remote"), emptySet()))
        first.await()
        second.await()
        like.await()
        assertEquals(1, requests)
        assertEquals(setOf("new", "remote"), store.state.value.discourseIds)
        assertEquals(setOf("new", "remote"), settings.getStringSet(DiscourseLikesStore.DISCOURSES, emptySet()))
    }

    @Test
    fun parsesIdempotentUnlikeAndAudioParentCounts() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(UnlikeResponse::class.java)
        val audio = adapter.fromJson("""{"data":{"discourse_audio_id":"audio","liked_by_user_id":"user","unliked":true,"total_likes":0,"discourse_total_likes":7}}""")!!.data
        assertEquals(0, audio.totalLikes)
        assertEquals(7, audio.discourseTotalLikes)
        val discourse = adapter.fromJson("""{"data":{"discourse_id":"discourse","liked_by_user_id":"user","unliked":false,"total_likes":0}}""")!!.data
        assertFalse(discourse.unliked)
        assertNull(discourse.discourseTotalLikes)
    }

    @Test
    fun unlikeIsOptimisticUpdatesParentAndPersistsRemovalWithoutExtendingExpiry() = runBlocking {
        store.refresh { UserLikesResponse(setOf("parent"), setOf("audio")) }
        val fetchedAt = settings.getLong(DiscourseLikesStore.FETCHED_AT, -1)
        val response = CompletableDeferred<UnlikeData>()
        val target = DiscourseLikesStore.Target("audio", true)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            store.unlike("audio", true, 1, "parent") { response.await() }
        }
        assertFalse(store.state.value.liked(target))
        assertEquals(0, store.state.value.counts[target])
        assertEquals(setOf("audio"), settings.getStringSet(DiscourseLikesStore.AUDIOS, emptySet()))
        assertNull(store.like("audio", true, 0) { error("Tap while unlike is pending") })
        assertNull(store.unlike("audio", true, 1) { error("Duplicate unlike") })
        now++
        response.complete(UnlikeData(discourseAudioId = "audio", likedByUserId = "user",
            unliked = false, totalLikes = 0, discourseTotalLikes = 12))
        pending.await()
        assertEquals(12, store.state.value.counts[DiscourseLikesStore.Target("parent", false)])
        assertEquals(setOf("parent"), store.state.value.discourseIds)
        assertTrue(DiscourseLikesStore(settings).state.value.audioIds.isEmpty())
        assertEquals(fetchedAt, settings.getLong(DiscourseLikesStore.FETCHED_AT, -1))
        store.like("audio", true, 0) {
            LikeData(discourseAudioId = "audio", likedByUserId = "user", liked = true, totalLikes = 1)
        }
        assertTrue(store.state.value.liked(target))
        assertEquals(1, store.state.value.counts[target])
    }

    @Test
    fun failedAndCancelledUnlikesRestoreConfirmedStateAndCount() = runBlocking {
        store.like("discourse", false, 3) {
            LikeData(discourseId = "discourse", likedByUserId = "user", liked = true, totalLikes = 4)
        }
        val target = DiscourseLikesStore.Target("discourse", false)
        try {
            store.unlike("discourse", false, 4) { throw IOException("offline") }
            fail("Expected unlike failure")
        } catch (_: IOException) { }
        assertTrue(store.state.value.liked(target))
        assertEquals(4, store.state.value.counts[target])
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            store.unlike("discourse", false, 4) { CompletableDeferred<UnlikeData>().await() }
        }
        assertFalse(store.state.value.liked(target))
        waiting.cancel()
        waiting.join()
        assertTrue(store.state.value.liked(target))
        assertEquals(4, store.state.value.counts[target])
        assertTrue(store.state.value.pending.isEmpty())
    }

    @Test
    fun refreshCannotResurrectPendingOrConfirmedUnlike() = runBlocking {
        store.refresh { UserLikesResponse(setOf("discourse"), emptySet()) }
        now += DiscourseLikesStore.TTL
        val response = CompletableDeferred<UserLikesResponse>()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { store.refresh { response.await() } }
        val unlikeResponse = CompletableDeferred<UnlikeData>()
        val unlike = async(start = CoroutineStart.UNDISPATCHED) {
            store.unlike("discourse", false, 1) { unlikeResponse.await() }
        }
        response.complete(UserLikesResponse(setOf("discourse"), emptySet()))
        refresh.await()
        assertTrue(store.state.value.discourseIds.isEmpty())
        unlikeResponse.complete(UnlikeData(discourseId = "discourse", likedByUserId = "user", unliked = true, totalLikes = 0))
        unlike.await()
        now += DiscourseLikesStore.TTL
        store.refresh { UserLikesResponse(emptySet(), emptySet()) }
        assertTrue(store.state.value.discourseIds.isEmpty())
        assertTrue(DiscourseLikesStore(settings).state.value.discourseIds.isEmpty())
    }

    @Test
    fun sharedStoreIsReusedAcrossRepositoryConsumers() {
        assertSame(DiscourseLikesStore.shared(settings), DiscourseLikesStore.shared(settings))
    }
}
