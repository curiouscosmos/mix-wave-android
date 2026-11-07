package org.videolan.mobile.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import com.onesignal.user.subscriptions.IPushSubscriptionObserver
import com.onesignal.user.subscriptions.PushSubscriptionChangedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.AppScope
import org.videolan.vlc.gui.MainActivity
import java.lang.ref.WeakReference

/** Keeps SDK calls and the registration/permission flow in the application module. */
internal object OneSignalManager : Application.ActivityLifecycleCallbacks {
    private const val APP_ID = "4b95e8e9-a860-4acc-99b5-30dfaa2b978b"
    private const val DIALOG_SHOWN = "integration_dialog_shown"
    private var activity = WeakReference<Activity>(null)
    private var ready = false
    private val observer = object : IPushSubscriptionObserver {
        override fun onPushSubscriptionChange(state: PushSubscriptionChangedState) {
            AppScope.launch(Dispatchers.Main) { showRegistrationDialog() }
        }
    }

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        AppScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                OneSignal.Debug.logLevel = if (BuildConfig.DEBUG) LogLevel.VERBOSE else LogLevel.NONE
                OneSignal.initWithContext(application, APP_ID)
            }
            OneSignal.User.pushSubscription.addObserver(observer)
            ready = true
            showRegistrationDialog()
        }
    }

    private fun showRegistrationDialog() {
        if (!ready || !isRegistered(OneSignal.User.pushSubscription.id)) return
        val current = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val preferences = current.getSharedPreferences("onesignal_integration", Application.MODE_PRIVATE)
        if (preferences.getBoolean(DIALOG_SHOWN, false)) return
        AlertDialog.Builder(current)
            .setTitle(R.string.onesignal_integration_title)
            .setMessage(R.string.onesignal_integration_message)
            .setPositiveButton(R.string.onesignal_integration_confirm) { _, _ ->
                AppScope.launch(Dispatchers.Main) { OneSignal.Notifications.requestPermission(true) }
            }
            .setCancelable(false)
            .show()
        preferences.edit().putBoolean(DIALOG_SHOWN, true).apply()
    }

    internal fun isRegistered(id: String?) = !id.isNullOrBlank() && !id.startsWith("local-")

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        this.activity = WeakReference(activity)
        showRegistrationDialog()
    }

    override fun onActivityPaused(activity: Activity) {
        if (this.activity.get() === activity) this.activity.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
