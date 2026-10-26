package org.videolan.vlc.discourse

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
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
