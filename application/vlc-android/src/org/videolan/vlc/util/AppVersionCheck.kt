package org.videolan.vlc.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.videolan.vlc.R
import java.util.concurrent.TimeUnit

internal fun isVersionLower(current: String, minimum: String): Boolean {
    fun parts(version: String): List<java.math.BigInteger>? {
        val parts = version.split('.')
        if (parts.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } }) return null
        return parts.map { it.toBigInteger() }
    }
    val currentParts = parts(current) ?: return false
    val minimumParts = parts(minimum) ?: return false
    for (index in 0 until maxOf(currentParts.size, minimumParts.size)) {
        val comparison = (currentParts.getOrNull(index) ?: java.math.BigInteger.ZERO)
            .compareTo(minimumParts.getOrNull(index) ?: java.math.BigInteger.ZERO)
        if (comparison != 0) return comparison < 0
    }
    return false
}

class AppVersionCheck(private val activity: FragmentActivity) {
    private var job: Job? = null
    private var dialog: AlertDialog? = null

    fun check() {
        if (job?.isActive == true) return
        job = activity.lifecycleScope.launch {
            try {
                val policy = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder()
                        .url("https://osho.b-cdn.net/app/version_check.json")
                        .header("Cache-Control", "no-cache, no-store")
                        .build()).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        JSONObject(response.body?.string() ?: return@withContext null)
                            .getJSONObject("android")
                    }
                } ?: return@launch
                val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                    ?: return@launch
                val needsUpdate = isVersionLower(current, policy.getString("minSupportedVersion"))
                if (!needsUpdate) {
                    dialog?.dismiss()
                    return@launch
                }
                if (dialog?.isShowing == true || activity.isFinishing || activity.isDestroyed) return@launch
                val forced = policy.getBoolean("forceUpdate")
                dialog = AlertDialog.Builder(activity)
                    .setTitle(R.string.app_update_title)
                    .setMessage(R.string.app_update_message)
                    .setCancelable(!forced)
                    .setPositiveButton(R.string.app_update_action, null)
                    .apply {
                        if (!forced) setNegativeButton(R.string.app_update_skip) { _, _ -> }
                    }
                    .create().also { updateDialog ->
                        updateDialog.setCanceledOnTouchOutside(!forced)
                        updateDialog.show()
                        // Keep a mandatory update blocking the app after opening the store.
                        updateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            try {
                                activity.startActivity(Intent(Intent.ACTION_VIEW,
                                    "https://play.google.com/store/apps/details?id=${activity.packageName.removeSuffix(".debug")}".toUri()))
                                if (!forced) updateDialog.dismiss()
                            } catch (error: ActivityNotFoundException) {
                                Log.w("AppVersionCheck", "No app can open the Play Store listing", error)
                            }
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("AppVersionCheck", "Unable to check app version", error)
            }
        }
    }

    private companion object {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
