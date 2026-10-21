/*****************************************************************************
 * VLCApplication.ki
 *
 * Copyright © 2010-2020 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 */
package org.videolan.mobile.app

import android.annotation.TargetApi
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import org.videolan.libvlc.Dialog
import org.videolan.tools.BitmapCache
import org.videolan.tools.HttpImageLoader
import org.videolan.vlc.ArtworkProvider
import org.videolan.vlc.util.DialogDelegate

private const val TAG = "VLC/VLCApplication"

class VLCApplication : MultiDexApplication(), Dialog.Callbacks by DialogDelegate, AppDelegate by AppSetupDelegate() {

    init {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
    }

    @TargetApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        try {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                options.environment = if (BuildConfig.DEBUG) "debug" else "production"
                options.isAttachThreads = true
            }
            if (Sentry.isEnabled()) startMainThreadWatchdog()
            else Log.e(TAG, "Sentry is disabled: no DSN configured")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to initialize Sentry; continuing without error reporting", error)
        }
        HttpImageLoader.initialize(this)
        setupApplication()
    }

    private fun startMainThreadWatchdog() {
        // SDK ANRs on Android 11+ depend on OS exit records; capture live stalls too.
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)
        // Uptime excludes deep sleep during screen-off playback.
        val watchdog = MainThreadWatchdog({ handler.post(it) }, SystemClock::uptimeMillis) {
            val error = IllegalStateException("Main thread blocked for at least ${MainThreadWatchdog.TIMEOUT_MS} ms")
            error.stackTrace = mainLooper.thread.stackTrace
            Sentry.captureException(error) { scope ->
                scope.setTag("failure.type", "main_thread_stall")
            }
        }
        Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    watchdog.check(Debug.isDebuggerConnected() || Debug.waitingForDebugger())
                    Thread.sleep(1_000L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                Log.e(TAG, "Main thread watchdog stopped", error)
            }
        }, "main-thread-watchdog").apply { isDaemon = true }.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appContextProvider.updateContext()
    }

    /**
     * Called when the overall system is running low on memory
     */
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "System is running low on memory")
        BitmapCache.clear()
        ArtworkProvider.clear()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "onTrimMemory, level: $level")
        BitmapCache.clear()
        ArtworkProvider.clear()
    }
}
