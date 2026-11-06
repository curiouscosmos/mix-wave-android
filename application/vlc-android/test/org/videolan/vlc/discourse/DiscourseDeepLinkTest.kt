package org.videolan.vlc.discourse

import android.net.Uri
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
class DiscourseDeepLinkTest {
    @Test
    fun discoursePageIsParsed() {
        val link = DiscourseDeepLink.parse(Uri.parse("https://rajneeshdiscourse.com/discourses/discourse-id"))
        assertEquals(DiscourseDeepLink("discourse-id", null), link)
    }

    @Test
    fun trackPageIsParsedWithAudioId() {
        val link = DiscourseDeepLink.parse(
            Uri.parse("https://rajneeshdiscourse.com/discourses/discourse-id/tracks/audio-id?x=1")
        )
        assertEquals(DiscourseDeepLink("discourse-id", "audio-id"), link)
    }

    @Test
    fun clipSecondsAreConvertedToMilliseconds() {
        val base = "https://rajneeshdiscourse.com/discourses/id/tracks/audio"
        assertEquals(ClipRange(11_000, 120_000), DiscourseDeepLink.parse(Uri.parse("$base?start=11&end=120"))?.clipRange)
        assertEquals(ClipRange(11_500, 120_250), DiscourseDeepLink.parse(Uri.parse("$base?start=11.5&end=120.25"))?.clipRange)
    }

    @Test
    fun invalidClipTimesKeepNormalTrackLink() {
        val base = "https://rajneeshdiscourse.com/discourses/id/tracks/audio"
        listOf("start=11", "end=120", "start=-1&end=120", "start=120&end=11",
            "start=11&end=11", "start=NaN&end=120", "start=0&end=Infinity",
            "start=0&end=999999999999999999999", "start=0&end=0.5").forEach { query ->
            assertEquals(DiscourseDeepLink("id", "audio"), DiscourseDeepLink.parse(Uri.parse("$base?$query")))
        }
        assertNull(DiscourseDeepLink.parse(Uri.parse("https://rajneeshdiscourse.com/discourses/id?start=11&end=120"))?.clipRange)
    }

    @Test
    fun trailingSlashAndWwwHostAreAccepted() {
        assertEquals(
            DiscourseDeepLink("discourse-id", null),
            DiscourseDeepLink.parse(Uri.parse("https://www.rajneeshdiscourse.com/discourses/discourse-id/"))
        )
    }

    @Test
    fun unrelatedUrlsAreRejected() {
        assertNull(DiscourseDeepLink.parse(Uri.parse("https://rajneeshdiscourse.com/")))
        assertNull(DiscourseDeepLink.parse(Uri.parse("https://rajneeshdiscourse.com/discourses")))
        assertNull(DiscourseDeepLink.parse(Uri.parse("https://rajneeshdiscourse.com/discourses/id/other/audio")))
        assertNull(DiscourseDeepLink.parse(Uri.parse("https://example.com/discourses/id")))
        assertNull(DiscourseDeepLink.parse(Uri.parse("mailto:test@example.com")))
    }
}
