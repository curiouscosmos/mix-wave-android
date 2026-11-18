package org.videolan.vlc.gui.audio

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import org.videolan.vlc.R

internal class ListeningStatsDashboard(
    private val root: LinearLayout,
    private val duration: (Long) -> String,
    initialPeriod: Int
) {
    private val context = root.context
    private val primaryTextColor = context.getColor(R.color.stats_text)
    private val secondary = context.getColor(R.color.stats_secondary)
    private val accent = context.getColor(R.color.stats_accent)
    private val border = context.getColor(R.color.stats_border)
    private val tile = context.getColor(R.color.stats_tile)
    private val periods = listOf(R.string.listening_stats_today, R.string.stats_period_week, R.string.stats_period_month,
        R.string.stats_period_year, R.string.stats_period_all)
    private val windows = listOf(R.string.listening_stats_today, R.string.listening_stats_week, R.string.listening_stats_month,
        R.string.listening_stats_year, R.string.listening_stats_overall)
    var period = initialPeriod.coerceIn(periods.indices)
        private set
    private var trend: ListeningStatsTrend? = null

    private val tabs: List<TextView>
    private val ring = ListeningStatsRing(context)
    private val heroDuration = value(27f).apply {
        gravity = Gravity.CENTER
        setText(R.string.loading)
    }
    private val heroPeriod = label(14f).apply { gravity = Gravity.CENTER }
    private val heroTracks: TextView
    private val heroDiscourses: TextView
    private val bars = ListeningStatsChart(context, heatmap = false, duration)
    private val heatmap = ListeningStatsChart(context, heatmap = true, duration)
    private val tiles: List<TextView>
    private val library: List<TextView>
    private val clips: List<TextView>
    private val clipBadges: List<TextView>
    private val clipNote: TextView
    private val downloaded: TextView
    private val insights: List<TextView>

    init {
        val segmented = row().apply {
            background = rounded(tile, 28, border)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        root.withTopMargin(segmented, 8)
        tabs = periods.mapIndexed { index, title ->
            label(13f).apply {
                setText(title)
                gravity = Gravity.CENTER
                minHeight = dp(42)
                isFocusable = true
                segmented.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { period = index; updateOverview() }
            }
        }

        val overview = panel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dial = FrameLayout(context).apply {
            addView(ring, FrameLayout.LayoutParams(-1, -1))
            addView(column().apply {
                gravity = Gravity.CENTER
                addView(icon(R.drawable.ic_search_headphones, 28))
                addView(heroDuration)
                addView(heroPeriod)
                setPadding(dp(18), 0, dp(18), 0)
            }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        overview.addView(dial, LinearLayout.LayoutParams(0, dp(158), 1.6f))
        overview.addView(View(context).apply { setBackgroundColor(border) },
            LinearLayout.LayoutParams(dp(1), dp(126)).apply { marginStart = dp(14); marginEnd = dp(14) })
        val heroMetrics = column()
        overview.addView(heroMetrics, LinearLayout.LayoutParams(0, -2, 1f))
        heroMetrics.addView(label(11f).apply { setText(R.string.listening_stats_overall) })
        heroTracks = metric(heroMetrics, R.string.tracks, 26f)
        heroDiscourses = metric(heroMetrics, R.string.discourses, 26f, top = 12)

        panel(R.string.listening_stats_time).apply {
            withTopMargin(label(12f).apply { setText(R.string.stats_last_seven_days) }, 2)
            withTopMargin(bars, 4, 155)
        }

        tiles = listOf(0, 1, 2, 4).chunked(2).flatMap { indices ->
            val pair = row()
            root.withTopMargin(pair, 10)
            indices.mapIndexed { index, window ->
                val box = column().apply {
                    setPadding(dp(13), dp(10), dp(13), dp(10))
                    background = rounded(tile, 16)
                    addView(icon(R.drawable.ic_discourse_calendar, 23))
                }
                pair.addView(box, weighted(index))
                metric(box, windows[window], 20f, top = 3)
            }
        }

        panel(R.string.stats_listening_activity).apply { withTopMargin(heatmap, 14, 147) }

        val libraryPanel = panel(R.string.listening_stats_library, R.drawable.ic_discourse_tracks, wave = true)
        library = listOf(R.string.listening_stats_tracks, R.string.listening_stats_discourses,
            R.string.listening_stats_repeat_tracks, R.string.listening_stats_repeat_discourses).chunked(2).flatMap { labels ->
            val line = row()
            libraryPanel.withTopMargin(line, 15)
            labels.mapIndexed { index, title ->
                val cell = column()
                if (index == 1) line.addView(View(context).apply { setBackgroundColor(border) },
                    LinearLayout.LayoutParams(dp(1), -1).apply { marginEnd = dp(12) })
                line.addView(cell, weighted(index))
                metric(cell, title, 30f)
            }
        }

        val clipPanel = panel(R.string.listening_stats_clips, R.drawable.ic_player_clip)
        val total = metric(clipPanel, R.string.listening_stats_clip_count, 34f, top = 12)
        val topTrack = detail(clipPanel, R.drawable.ic_player_play_solid, R.string.listening_stats_clip_track, rank = true)
        val topDiscourse = detail(clipPanel, R.drawable.ic_discourse_tracks, R.string.listening_stats_clip_discourse, rank = true)
        clips = listOf(total, topTrack.first, topDiscourse.first)
        clipBadges = listOf(topTrack.second, topDiscourse.second)
        clipNote = label(12f).apply {
            setText(R.string.listening_stats_clip_legacy)
            isVisible = false
            clipPanel.withTopMargin(this, 6)
        }

        downloaded = metric(panel(R.string.listening_stats_download_section, R.drawable.ic_discourse_download, wave = true),
            R.string.listening_stats_downloads, 34f, top = 12)

        val insightsPanel = panel(R.string.stats_insights, R.drawable.ic_information)
        insights = listOf(
            detail(insightsPanel, R.drawable.ic_history, R.string.listening_stats_busiest_day).first,
            detail(insightsPanel, R.drawable.ic_player_timer, R.string.stats_average_day).first,
            detail(insightsPanel, R.drawable.ic_discourse_calendar, R.string.stats_streak).first
        )
        updateOverview()
    }

    fun bindTime(trend: ListeningStatsTrend, busiest: String, average: String, streak: String) {
        this.trend = trend
        updateOverview()
        bars.bind(trend)
        heatmap.bind(trend)
        render(tiles, listOf(trend.time.today, trend.time.week, trend.time.month, trend.time.overall).map(duration))
        render(insights, listOf(busiest, average, streak))
    }

    fun bindLibrary(counts: List<String>) {
        render(library, counts)
        heroTracks.text = counts[0]
        heroDiscourses.text = counts[1]
    }

    fun bindClips(total: String, track: String, trackCount: String?, discourse: String, discourseCount: String?, legacy: Boolean) {
        render(clips, listOf(total, track, discourse))
        clipBadges.zip(listOf(trackCount, discourseCount)).forEach { (badge, count) ->
            badge.isVisible = count != null
            badge.text = count
        }
        clipNote.isVisible = legacy
    }

    fun bindDownloads(count: String) {
        downloaded.text = count
    }

    fun timeError(error: String) = render(tiles + insights + heroDuration, List(8) { error })
    fun libraryError(error: String) = bindLibrary(List(4) { error })
    fun clipsError(error: String) = bindClips(error, error, null, error, null, false)

    private fun updateOverview() {
        tabs.forEachIndexed { index, tab ->
            tab.isSelected = index == period
            tab.setTextColor(if (tab.isSelected) context.getColor(R.color.stats_on_accent) else primaryTextColor)
            tab.background = if (tab.isSelected) rounded(accent, 25) else null
        }
        heroPeriod.setText(windows[period])
        val data = trend ?: return
        val totals = data.time
        val selected = listOf(totals.today, totals.week, totals.month, totals.year, totals.overall)[period]
        heroDuration.text = duration(selected)
        val peak = data.periodPeaks[period]
        ring.fraction = if (peak > 0) (selected.toDouble() / peak).toFloat().coerceIn(0f, 1f) else 0f
    }

    private fun panel(@StringRes title: Int? = null, @DrawableRes image: Int? = null, wave: Boolean = false): LinearLayout {
        val shape = rounded(context.getColor(R.color.stats_surface), 19, border).apply {
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(context.getColor(R.color.stats_surface), context.getColor(R.color.stats_surface_end))
        }
        return column().apply {
            background = if (wave) LayerDrawable(arrayOf(shape, context.getDrawable(R.drawable.discourse_card_wave)!!.mutate().apply {
                setTint(accent)
                alpha = 105
            })).apply {
                setLayerHeight(1, dp(78))
                setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            } else shape
            setPadding(dp(15), dp(13), dp(15), dp(13))
            elevation = dp(1).toFloat()
            clipToOutline = true
            root.withTopMargin(this, 14)
            if (title != null) addView(row().apply {
                if (image != null) addView(icon(image, 26), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(9) })
                addView(value(20f).apply {
                    setText(title)
                    ViewCompat.setAccessibilityHeading(this, true)
                })
            })
        }
    }

    private fun metric(parent: LinearLayout, @StringRes title: Int, size: Float, top: Int = 0): TextView =
        value(size).apply {
            parent.withTopMargin(this, top)
            parent.addView(label(13f).apply { setText(title) })
        }

    private fun detail(parent: LinearLayout, @DrawableRes icon: Int, @StringRes title: Int, rank: Boolean = false): Pair<TextView, TextView> {
        val line = row().apply {
            background = rounded(tile, 13)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            addView(icon(icon, 23))
        }
        val content = column()
        line.addView(content, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        val detail = if (rank) value(15f) else label(14f)
        val heading = (if (rank) label(13f) else value(14f)).apply { setText(title) }
        content.addView(if (rank) detail else heading)
        content.addView(if (rank) heading else detail)
        val badge = value(15f).apply {
            setTextColor(accent)
            background = rounded(context.getColor(R.color.stats_surface), 20)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            isVisible = false
        }
        if (rank) line.addView(badge)
        parent.withTopMargin(line, 8)
        return detail to badge
    }

    private fun icon(@DrawableRes id: Int, size: Int) = ImageView(context).apply {
        setImageResource(id)
        imageTintList = ColorStateList.valueOf(accent)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun value(size: Float) = label(size).apply {
        setTextColor(primaryTextColor)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun label(size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(secondary)
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun weighted(index: Int) = LinearLayout.LayoutParams(0, -2, 1f).apply {
        if (index == 1) marginStart = dp(8)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun LinearLayout.withTopMargin(view: View, top: Int, height: Int = -2) {
        addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { topMargin = dp(top) })
    }

    private fun render(views: List<TextView>, values: List<String>) {
        views.zip(values).forEach { (view, value) -> view.text = value }
    }
}
