package org.videolan.vlc.gui.dialogs

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.vlc.R
import org.videolan.vlc.discourse.ClipExportOptions
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.discourse.DiscourseAudio

class ClipDialogTestActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_VLC_Black)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val audio = DiscourseAudio("test", "discourse", "Test", null, "english", "Test clip",
                "https://example.invalid/test.mp3", 6.0, 100, "audio/mpeg", 1, "", "")
            ClipDownloadDialog.newInstance(audio, ClipRange(1_000, 3_000), ClipExportOptions())
                .showNow(supportFragmentManager, "clip")
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ClipDownloadDialogTest {
    @Test fun pickerCancellationAndRotationKeepBothActionsWithoutExporting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            ActivityScenario.launch<ClipDialogTestActivity>(Intent(context, ClipDialogTestActivity::class.java)).use { scenario ->
                for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    awaitDialog(scenario, orientation)
                    onView(withText(R.string.clip_download)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
                    onView(withText(R.string.clip_create_video)).inRoot(isDialog()).perform(scrollTo(), click())
                    onView(withText(R.string.clip_create_video)).inRoot(isDialog()).check(matches(isDisplayed()))
                    assertFalse(context.getSharedPreferences("discourse_clip_export", 0).contains("request"))
                }
                scenario.recreate()
                awaitDialog(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                onView(withText(R.string.clip_download)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
            }
            intended(hasAction(Intent.ACTION_OPEN_DOCUMENT), androidx.test.espresso.intent.VerificationModes.times(2))
        } finally { Intents.release() }
    }

    private fun awaitDialog(scenario: ActivityScenario<ClipDialogTestActivity>, orientation: Int) {
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity {
                val dialog = it.supportFragmentManager.findFragmentByTag("clip") as? ClipDownloadDialog
                ready = it.resources.configuration.orientation == expected && dialog?.dialog?.window?.decorView?.hasWindowFocus() == true
            }
            if (ready) return
            Thread.sleep(50)
        }
        org.junit.Assert.fail("Dialog did not regain focus after rotation")
    }
}
