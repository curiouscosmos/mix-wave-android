package org.videolan.vlc.gui

import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.videolan.resources.EXTRA_TARGET
import org.videolan.vlc.BaseUITest
import org.videolan.vlc.R
import org.videolan.vlc.gui.audio.HomeFragment

class MainActivityNavigationUITest : BaseUITest() {
    @Rule
    @JvmField
    val activityTestRule = ActivityTestRule(MainActivity::class.java, true, false)

    private lateinit var activity: MainActivity

    override fun beforeTest() {
        activityTestRule.launchActivity(Intent().putExtra(EXTRA_TARGET, R.id.nav_audio))
        activity = activityTestRule.activity
    }

    @Test
    fun homeNavigationSurvivesActivityRestart() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle()

        onView(withId(R.id.nav_audio)).perform(click())

        assertTrue(
            activity.supportFragmentManager.findFragmentById(R.id.fragment_placeholder) is HomeFragment
        )
    }
}
