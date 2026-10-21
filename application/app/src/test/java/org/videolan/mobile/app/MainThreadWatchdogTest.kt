package org.videolan.mobile.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MainThreadWatchdogTest {
    private var uptime = 0L
    private val queued = ArrayList<Runnable>()
    private var reports = 0
    private val watchdog = MainThreadWatchdog({ queued.add(it) }, { uptime }, { reports++ })

    @Test
    fun reportsBlockedMainThreadWithoutAnAndroidAnr() {
        watchdog.check()
        uptime = MainThreadWatchdog.TIMEOUT_MS - 1
        watchdog.check()
        assertEquals(0, reports)
        uptime++
        watchdog.check()
        assertEquals(1, reports)
        uptime += MainThreadWatchdog.TIMEOUT_MS
        watchdog.check()
        assertEquals(1, reports)
        assertEquals(1, queued.size)
    }

    @Test
    fun reportsAgainOnlyAfterMainThreadRecovers() {
        watchdog.check()
        uptime += MainThreadWatchdog.TIMEOUT_MS
        watchdog.check()
        queued.removeAt(0).run()
        watchdog.check()
        uptime += MainThreadWatchdog.TIMEOUT_MS
        watchdog.check()
        assertEquals(2, reports)
    }

    @Test
    fun responsiveMainThreadDoesNotReport() {
        repeat(30) {
            watchdog.check()
            queued.removeAt(0).run()
            uptime += 1_000L
        }
        assertEquals(0, reports)
    }

    @Test
    fun debuggerPauseIsIgnoredUntilDebuggerDisconnects() {
        watchdog.check()
        uptime += MainThreadWatchdog.TIMEOUT_MS
        watchdog.check(debuggerConnected = true)
        assertEquals(0, reports)
        watchdog.check()
        assertEquals(1, reports)
    }

    @Test
    fun elapsedWallTimeDuringDeepSleepDoesNotCountAsBlockedUptime() {
        watchdog.check()
        // Android uptime excludes deep sleep, so the injected clock remains unchanged.
        repeat(30) { watchdog.check() }
        assertEquals(0, reports)
        assertEquals(1, queued.size)
    }
}
