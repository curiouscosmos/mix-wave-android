package org.videolan.vlc.discourse

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import com.sun.net.httpserver.HttpServer
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue

class DiscourseCachePolicyTest {
    @Test
    fun serverErrorsRemainReadableAndUsePrimedCache() = withServer { client, url, fail ->
        val request = Request.Builder().url(url).header("Cache-Control", FORCE_REFRESH).build()
        fail()
        client.newCall(request).execute().use {
            assertEquals(500, it.code)
            assertEquals("fixture", it.header("X-Test"))
            assertEquals("unavailable", it.body!!.string())
        }
        client.newCall(request.newBuilder().url("$url/prime").build()).execute().use {
            assertEquals("cached", it.body!!.string())
        }
        client.newCall(request.newBuilder().url("$url/prime").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("cached", it.body!!.string())
        }
    }

    @Test
    fun repeatedAsyncServerErrorsReachCallbacks() = withServer { client, url, fail ->
        fail()
        val completed = CountDownLatch(8)
        val errors = ConcurrentLinkedQueue<String>()
        repeat(8) {
            client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    errors.add(e.toString())
                    completed.countDown()
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.code != 500 || it.body!!.string() != "unavailable") errors.add("Unexpected response")
                        }
                    } finally { completed.countDown() }
                }
            })
        }
        assertTrue("Missing callbacks", completed.await(10, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test
    fun networkFailureUsesRealCache() = withServer { client, url, _ ->
        val request = Request.Builder().url(url).header("Cache-Control", FORCE_REFRESH).build()
        client.newCall(request).execute().use { assertEquals("cached", it.body!!.string()) }
        val offline = client.newBuilder().addNetworkInterceptor { throw IOException("offline") }.build()
        offline.newCall(request).execute().use { assertEquals("cached", it.body!!.string()) }
    }

    @Test
    fun mutationsDoNotAttemptFallback() {
        val request = Request.Builder().url("https://example.test/discourses/id/like")
            .put(okhttp3.RequestBody.create(null, byteArrayOf())).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } throws IOException("offline")
        try {
            StaleCacheInterceptor().intercept(chain)
            org.junit.Assert.fail("Expected mutation failure")
        } catch (_: IOException) { }
        io.mockk.verify(exactly = 1) { chain.proceed(any()) }
    }

    @Test
    fun cacheFailureIsNotRetried() {
        val request = Request.Builder().url("https://example.test/discourses").build()
        val chain = mockk<Interceptor.Chain>()
        val failure = IOException("cache unavailable")
        every { chain.request() } returns request
        every { chain.proceed(any()) } throws failure
        every { chain.proceed(request) } returns response(request).newBuilder().code(500)
            .body(okhttp3.ResponseBody.create(null, "unavailable")).build()
        try {
            StaleCacheInterceptor().intercept(chain)
            org.junit.Assert.fail("Expected cache failure")
        } catch (error: IOException) { assertSame(failure, error) }
        io.mockk.verify(exactly = 2) { chain.proceed(any()) }
    }

    private fun withServer(test: (OkHttpClient, String, () -> Unit) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val failing = AtomicBoolean(false)
        val primed = AtomicBoolean(false)
        server.createContext("/") { exchange ->
            val success = !failing.get() || (exchange.requestURI.path == "/prime" && !primed.getAndSet(true))
            val bytes = (if (success) "cached" else "unavailable").toByteArray()
            exchange.responseHeaders.add("X-Test", "fixture")
            exchange.sendResponseHeaders(if (success) 200 else 500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val directory = Files.createTempDirectory("discourse-cache").toFile()
        val cache = Cache(directory, 1024 * 1024)
        val client = OkHttpClient.Builder().cache(cache).addInterceptor(StaleCacheInterceptor())
            .addNetworkInterceptor(CacheResponseInterceptor()).build()
        server.start()
        try { test(client, "http://127.0.0.1:${server.address.port}") { failing.set(true) } }
        finally {
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            cache.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun likesAreNeverHttpCachedOrServedFromStaleFallback() {
        val request = Request.Builder().url("https://example.test/likes?user_id=user")
            .header("Cache-Control", "no-cache, no-store").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response(request)
        assertEquals("no-store", CacheResponseInterceptor().intercept(chain).header("Cache-Control"))
        every { chain.proceed(request) } throws IOException("offline")
        try {
            StaleCacheInterceptor().intercept(chain)
            org.junit.Assert.fail("Expected network failure, not a cached response")
        } catch (_: IOException) { }
        io.mockk.verify(exactly = 2) { chain.proceed(request) }
        io.mockk.verify(exactly = 2) { chain.request() }
        io.mockk.confirmVerified(chain)
    }

    @Test
    fun appliesGetOnlyPolicyAndFallsBackToStaleCache() {
        assertEquals(FORCE_REFRESH, cacheControl(true))
        assertEquals(null, cacheControl(false))
        val get = Request.Builder().url("https://example.test/discourses?page=1")
            .header("Cache-Control", FORCE_REFRESH).build()
        val put = get.newBuilder().method("PUT", okhttp3.RequestBody.create(null, byteArrayOf())).build()
        val responseChain = mockk<Interceptor.Chain>()
        every { responseChain.request() } returns get
        every { responseChain.proceed(get) } returns response(get)
        assertEquals("public, max-age=86400", CacheResponseInterceptor().intercept(responseChain).header("Cache-Control"))

        every { responseChain.request() } returns put
        every { responseChain.proceed(put) } returns response(put)
        assertEquals(null, CacheResponseInterceptor().intercept(responseChain).header("Cache-Control"))

        val stale = response(get)
        val fallbackChain = mockk<Interceptor.Chain>()
        val fallbackRequest = slot<Request>()
        every { fallbackChain.request() } returns get
        every { fallbackChain.proceed(capture(fallbackRequest)) } returns stale
        every { fallbackChain.proceed(get) } throws IOException("offline")
        assertSame(stale, StaleCacheInterceptor().intercept(fallbackChain))
        assertEquals("only-if-cached, max-stale=2147483647", fallbackRequest.captured.header("Cache-Control"))
    }

    private fun response(request: Request) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
}
