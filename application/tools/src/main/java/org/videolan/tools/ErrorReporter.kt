package org.videolan.tools

import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel

object ErrorReporter {
    @JvmStatic
    fun error(tag: String, message: String?, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (throwable != null) {
            Sentry.captureException(throwable) { scope ->
                scope.setTag("log.tag", tag)
                message?.let { scope.setExtra("log.message", it) }
            }
        } else if (!message.isNullOrBlank()) {
            Sentry.captureMessage("$tag: $message", SentryLevel.ERROR)
        }
    }
}
