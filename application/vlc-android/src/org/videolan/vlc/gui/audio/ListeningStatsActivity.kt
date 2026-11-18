package org.videolan.vlc.gui.audio

import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.vlc.R
import org.videolan.vlc.discourse.DiscourseDownloadStore
import org.videolan.vlc.discourse.DiscourseFavouritesStore
import org.videolan.vlc.discourse.DiscourseRepository
import org.videolan.vlc.media.ListeningStatsStore
import org.videolan.vlc.mediadb.models.ClipTrackCount

class ListeningStatsActivity : AppCompatActivity() {
    private val locale get() = resources.configuration.locales[0]
    private val numbers by lazy { NumberFormat.getIntegerInstance(locale) }
    private lateinit var dashboard: ListeningStatsDashboard
    private data class ClipStats(
        val total: Int, val track: ClipTrackCount?, val discourse: Pair<String?, Int>?, val hasUnknownDiscourse: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_listening_stats)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.listening_stats_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        findViewById<MaterialToolbar>(R.id.listening_stats_toolbar).apply {
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        val content = findViewById<LinearLayout>(R.id.listening_stats_sections)
        dashboard = ListeningStatsDashboard(content, ::duration, ::chartDuration, savedInstanceState?.getInt("period") ?: 0)
        findViewById<MaterialToolbar>(R.id.listening_stats_toolbar).menu.add(R.string.stats_calendar).apply {
            setIcon(R.drawable.ic_discourse_calendar)
            icon?.setTint(getColor(R.color.stats_text))
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { dashboard.toggleCalendar(); true }
        }
        val store = ListeningStatsStore.getInstance(this)
        val listening = store.database.listeningStatsDao()
        val downloadStore = DiscourseDownloadStore(this)
        val dates = flow {
            while (true) {
                emit(LocalDate.now())
                delay(60_000)
            }
        }.distinctUntilChanged()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    listening.days().combine(dates) { days, today ->
                        withContext(Dispatchers.Default) { listeningStatsTrend(days, today) }
                    }.catch { dashboard.timeError(errorMessage(it)) }
                        .collect { trend ->
                            val busiest = trend.time.busiestDay?.let {
                                val date = LocalDate.ofEpochDay(it.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                                getString(R.string.listening_stats_value_detail, date, duration(it.durationMs))
                            } ?: getString(R.string.listening_stats_empty)
                            dashboard.bindTime(trend, busiest,
                                resources.getQuantityString(R.plurals.stats_streak_days, trend.streak, trend.streak))
                        }
                }
                launch {
                    listening.counts().catch { dashboard.libraryError(errorMessage(it)) }.collect { counts ->
                        dashboard.bindLibrary(listOf(counts.tracks, counts.discourses).map(numbers::format), counts.repeatedTracks)
                    }
                }
                launch {
                    store.database.clipHistoryDao().countsByTrack()
                        .map { rows -> withContext(Dispatchers.IO) { summarizeClips(resolveLegacyClips(rows)) } }
                        .catch { dashboard.clipsError(errorMessage(it)) }
                        .collect { stats ->
                            dashboard.bindClips(
                                numbers.format(stats.total),
                                stats.track?.let { it.trackTitle ?: getString(R.string.listening_stats_clip_unknown) }
                                    ?: getString(R.string.listening_stats_no_clips),
                                stats.track?.count?.let(numbers::format),
                                stats.discourse?.let { it.first ?: getString(R.string.discourse) }
                                    ?: getString(R.string.listening_stats_no_discourse_clips),
                                stats.discourse?.second?.let(numbers::format),
                                stats.hasUnknownDiscourse
                            )
                        }
                }
                launch {
                    downloadStore.summaryChanges().map { downloadStore.totalDownloadedCount() }
                        .distinctUntilChanged().catch { dashboard.bindDownloads(errorMessage(it)) }
                        .collect { dashboard.bindDownloads(numbers.format(it)) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("period", dashboard.period)
        super.onSaveInstanceState(outState)
    }

    private fun summarizeClips(rows: List<ClipTrackCount>): ClipStats {
        var total = 0
        var hasUnknown = false
        val discourses = mutableMapOf<String, Pair<String?, Int>>()
        for (row in rows) {
            total += row.count
            val id = row.discourseId
            if (id == null) hasUnknown = true
            else {
                val previous = discourses[id]
                discourses[id] = (row.discourseTitle ?: previous?.first) to (row.count + (previous?.second ?: 0))
            }
        }
        val track = rows.maxWithOrNull(compareBy<ClipTrackCount> { it.count }.thenByDescending { it.audioId })
        val discourse = discourses.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Pair<String?, Int>>> { it.value.second }.thenByDescending { it.key }
        )?.value
        return ClipStats(total, track, discourse, hasUnknown)
    }

    private fun resolveLegacyClips(rows: List<ClipTrackCount>): List<ClipTrackCount> {
        if (rows.none { it.discourseId == null }) return rows
        val cached = (DiscourseRepository(this).recentlyPlayedAudios +
            DiscourseFavouritesStore.shared(this).state.value.tracks.map { it.audio }).associateBy { it.id }
        return rows.map { row ->
            val audio = cached[row.audioId]
            if (row.discourseId == null && audio != null) row.copy(
                trackTitle = audio.title, discourseId = audio.discourseId, discourseTitle = audio.discourseName
            ) else row
        }
    }

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes >= 60 -> getString(R.string.listening_stats_hours_minutes, numbers.format(minutes / 60), numbers.format(minutes % 60))
            minutes == 0L && ms > 0 -> getString(R.string.listening_stats_under_minute)
            else -> getString(R.string.listening_stats_minutes, numbers.format(minutes))
        }
    }

    private fun chartDuration(ms: Long): String = when {
        ms in 1 until 60_000 -> getString(R.string.stats_compact_under_minute)
        ms >= 3_600_000 && ms % 3_600_000 == 0L -> getString(R.string.stats_whole_hours, numbers.format(ms / 3_600_000))
        else -> duration(ms)
    }

    private fun errorMessage(error: Throwable): String {
        Log.e("ListeningStats", "Unable to read stats", error)
        return getString(R.string.listening_stats_unavailable)
    }
}
