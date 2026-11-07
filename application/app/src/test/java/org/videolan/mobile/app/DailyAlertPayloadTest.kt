package org.videolan.mobile.app

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.vlc.discourse.DiscoursePlaybackIds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class DailyAlertPayloadTest {
    @Test
    fun dailyAlertRequiresBothIdsInTheNestedPayload() {
        assertEquals(DiscoursePlaybackIds("discourse-id", "track-id"), parse(
            """{"payload":{"type":"daily-alert","discourseId":" discourse-id ","discourse_audio_id":"track-id"}}"""))
    }

    @Test
    fun otherTypesAndMalformedPayloadsAreIgnored() {
        assertNull(OneSignalManager.dailyAlertTarget(null))
        listOf(
            "{}", """{"payload":null}""", """{"payload":"daily-alert"}""",
            """{"type":"daily-alert","discourseId":"d","discourse_audio_id":"a"}""",
            """{"payload":{"type":"other","discourseId":"d","discourse_audio_id":"a"}}""",
            """{"payload":{"type":"Daily-alert","discourseId":"d","discourse_audio_id":"a"}}""",
            """{"payload":{"discourseId":"d","discourse_audio_id":"a"}}""",
            """{"payload":{"type":"daily-alert","discourseId":"d"}}""",
            """{"payload":{"type":"daily-alert","discourse_audio_id":"a"}}"""
        ).forEach { assertNull(it, parse(it)) }
    }

    @Test
    fun idsMustBeNonblankStrings() {
        listOf("null", "123", "false", "{}", "[]", "\"\"", "\"   \"").forEach { invalid ->
            assertNull(parse("""{"payload":{"type":"daily-alert","discourseId":$invalid,"discourse_audio_id":"a"}}"""))
            assertNull(parse("""{"payload":{"type":"daily-alert","discourseId":"d","discourse_audio_id":$invalid}}"""))
        }
    }

    private fun parse(json: String) = OneSignalManager.dailyAlertTarget(JSONObject(json))
}
