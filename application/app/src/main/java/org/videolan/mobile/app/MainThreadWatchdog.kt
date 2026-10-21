package org.videolan.mobile.app

import java.util.concurrent.atomic.AtomicBoolean

internal class MainThreadWatchdog(
    private val postToMain: (Runnable) -> Unit,
    private val uptimeMillis: () -> Long,
    private val reportStall: () -> Unit
) {
    private val responsive = AtomicBoolean(true)
    private var pendingSince = 0L
    private var reported = false

    // Called only by the watchdog thread; at most one heartbeat is queued during a stall.
    fun check(debuggerConnected: Boolean = false) {
        val now = uptimeMillis()
        if (responsive.getAndSet(false)) {
            pendingSince = now
            reported = false
            postToMain(Runnable { responsive.set(true) })
        } else if (!reported && !debuggerConnected && now - pendingSince >= TIMEOUT_MS) {
            reported = true
            reportStall()
        }
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
