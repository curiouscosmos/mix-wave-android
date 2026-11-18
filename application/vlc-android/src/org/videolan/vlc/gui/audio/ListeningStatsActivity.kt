package org.videolan.vlc.gui.audio

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
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
import org.videolan.vlc.media.listeningTime
import org.videolan.vlc.mediadb.models.ClipTrackCount

class ListeningStatsActivity : AppCompatActivity() {
    private val locale get() = resources.configuration.locales[0]
    private val numbers by lazy { NumberFormat.getIntegerInstance(locale) }
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
        val time = section(content, R.string.listening_stats_time, R.string.listening_stats_today,
            R.string.listening_stats_week, R.string.listening_stats_month, R.string.listening_stats_year,
            R.string.listening_stats_overall, R.string.listening_stats_busiest_day)
        val library = section(content, R.string.listening_stats_library, R.string.listening_stats_tracks,
            R.string.listening_stats_discourses, R.string.listening_stats_repeat_tracks, R.string.listening_stats_repeat_discourses)
        val clips = section(content, R.string.listening_stats_clips, R.string.listening_stats_clip_count,
            R.string.listening_stats_clip_track, R.string.listening_stats_clip_discourse)
        val clipNote = TextView(this).apply {
            setText(R.string.listening_stats_clip_legacy)
            setTextColor(getColor(R.color.discourse_track_secondary))
            visibility = View.GONE
        }
        clips.last().let { (it.parent as LinearLayout).addView(clipNote) }
        val downloads = section(content, R.string.listening_stats_download_section, R.string.listening_stats_downloads)
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
                        withContext(Dispatchers.Default) { listeningTime(days, today) }
                    }.catch { showError(time, it) }
                        .collect { totals ->
                            val busiest = totals.busiestDay?.let {
                                val date = LocalDate.ofEpochDay(it.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                                getString(R.string.listening_stats_value_detail, date, duration(it.durationMs))
                            } ?: getString(R.string.listening_stats_empty)
                            render(time, listOf(totals.today, totals.week, totals.month, totals.year, totals.overall)
                                .map(::duration) + busiest)
                        }
                }
                launch {
                    listening.counts().catch { showError(library, it) }.collect { counts ->
                        render(library, listOf(counts.tracks, counts.discourses, counts.repeatedTracks, counts.repeatedDiscourses)
                            .map(numbers::format))
                    }
                }
                launch {
                    store.database.clipHistoryDao().countsByTrack()
                        .map { rows -> withContext(Dispatchers.IO) { summarizeClips(resolveLegacyClips(rows)) } }
                        .catch { showError(clips, it) }
                        .collect { stats ->
                            render(clips, listOf(
                                numbers.format(stats.total),
                                stats.track?.let { getString(R.string.listening_stats_value_detail,
                                    it.trackTitle ?: getString(R.string.listening_stats_clip_unknown), numbers.format(it.count)) }
                                    ?: getString(R.string.listening_stats_no_clips),
                                stats.discourse?.let { (title, count) -> getString(R.string.listening_stats_value_detail,
                                    title ?: getString(R.string.discourse), numbers.format(count)) }
                                    ?: getString(R.string.listening_stats_no_discourse_clips)
                            ))
                            clipNote.visibility = if (stats.hasUnknownDiscourse) View.VISIBLE else View.GONE
                        }
                }
                launch {
                    downloadStore.summaryChanges().map { downloadStore.totalDownloadedCount() }
                        .distinctUntilChanged().catch { showError(downloads, it) }
                        .collect { downloads.single().text = numbers.format(it) }
                }
            }
        }
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

    private fun section(parent: LinearLayout, @StringRes title: Int, @StringRes vararg labels: Int): List<TextView> {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.discourse_track_card)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding }
        }
        parent.addView(panel)
        panel.addView(TextView(this).apply {
            setText(title)
            setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
            setTextColor(getColor(R.color.discourse_track_text))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        return labels.map { label ->
            panel.addView(TextView(this).apply {
                setText(label)
                setTextColor(getColor(R.color.discourse_track_secondary))
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            TextView(this).apply {
                setText(R.string.loading)
                textSize = 18f
                setTextColor(getColor(R.color.discourse_track_text))
                panel.addView(this)
            }
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

    private fun render(views: List<TextView>, values: List<String>) {
        views.zip(values).forEach { (view, value) -> view.text = value }
    }

    private fun showError(views: List<TextView>, error: Throwable) {
        Log.e("ListeningStats", "Unable to read stats", error)
        views.forEach { it.setText(R.string.listening_stats_unavailable) }
    }
}
