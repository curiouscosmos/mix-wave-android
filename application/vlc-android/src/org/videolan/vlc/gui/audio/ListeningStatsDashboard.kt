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
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import org.videolan.vlc.R

internal class ListeningStatsDashboard(
    private val root: LinearLayout,
    private val duration: (Long) -> String,
    private val chartDuration: (Long) -> String,
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
    private val heroDuration = value(32f).apply {
        gravity = Gravity.CENTER
        setText(R.string.loading)
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(14, 32, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val heroPeriod = label(14f).apply { gravity = Gravity.CENTER }
    private val heroTracks: TextView
    private val heroDiscourses: TextView
    private val bars = ListeningStatsChart(context, heatmap = false, chartDuration)
    private val heatmap = ListeningStatsChart(context, heatmap = true, chartDuration)
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
        root.withTopMargin(segmented, 12)
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

        val overview = panel(top = 22).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dial = FrameLayout(context).apply {
            addView(ring, FrameLayout.LayoutParams(-1, -1))
            val center = column().apply {
                gravity = Gravity.CENTER
                addView(icon(R.drawable.ic_search_headphones, 28))
                addView(heroDuration, LinearLayout.LayoutParams(-1, dp(52)))
                addView(heroPeriod)
            }
            addView(center, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val diameter = minOf(right - left, bottom - top)
                val inset = (right - left - diameter) / 2 + dp(21)
                if (center.paddingLeft != inset) center.setPadding(inset, 0, inset, 0)
            }
        }
        overview.addView(dial, LinearLayout.LayoutParams(0, dp(190), 1.65f))
        overview.addView(View(context).apply { setBackgroundColor(border) },
            LinearLayout.LayoutParams(dp(1), dp(148)).apply { marginStart = dp(12); marginEnd = dp(16) })
        val heroMetrics = column()
        overview.addView(heroMetrics, LinearLayout.LayoutParams(0, -2, 1f))
        heroMetrics.addView(label(11f).apply { setText(R.string.listening_stats_overall) })
        heroTracks = metric(heroMetrics, R.string.tracks, 30f)
        heroDiscourses = metric(heroMetrics, R.string.discourses, 30f, top = 16)

        panel(R.string.listening_stats_time, info = R.string.stats_chart_info,
            infoIcon = R.drawable.ic_information).apply {
            withTopMargin(bars, 6, 177)
        }

        tiles = listOf(0, 1, 2, 4).chunked(2).flatMap { indices ->
            val pair = row()
            root.withTopMargin(pair, 10)
            indices.mapIndexed { index, window ->
                val box = column().apply {
                    setPadding(dp(13), dp(10), dp(13), dp(10))
                    background = rounded(tile, 16)
                    if (window == 4) addView(value(22f).apply {
                        text = "∞"
                        includeFontPadding = false
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(accent)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(26), dp(23))) else addView(icon(R.drawable.ic_discourse_calendar, 23))
                }
                pair.addView(box, weighted(index))
                metric(box, windows[window], 20f, top = 3)
            }
        }

        panel(R.string.stats_listening_activity, info = R.string.stats_heatmap_description)
            .apply { withTopMargin(heatmap, 14) }

        val libraryPanel = panel(R.string.listening_stats_library, R.drawable.ic_discourse_tracks, wave = true,
            info = R.string.stats_library_info)
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

        val clipPanel = panel(R.string.listening_stats_clips, R.drawable.ic_player_clip, info = R.string.stats_clips_info)
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

        downloaded = metric(panel(R.string.listening_stats_download_section, R.drawable.ic_discourse_download,
            wave = true, info = R.string.stats_downloads_info),
            R.string.listening_stats_downloads, 34f, top = 12)

        val insightsPanel = panel(R.string.stats_insights, R.drawable.ic_information)
        insights = listOf(
            detail(insightsPanel, R.drawable.ic_history, R.string.listening_stats_busiest_day, info = R.string.stats_busiest_info).first,
            detail(insightsPanel, R.drawable.ic_player_timer, R.string.stats_average_day, info = R.string.stats_average_info).first,
            detail(insightsPanel, R.drawable.ic_discourse_calendar, R.string.stats_streak, info = R.string.stats_streak_info).first
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
        heroDuration.text = chartDuration(selected)
        heroDuration.contentDescription = duration(selected)
        val peak = data.periodPeaks[period]
        ring.fraction = if (peak > 0) (selected.toDouble() / peak).toFloat().coerceIn(0f, 1f) else 0f
    }

    private fun panel(
        @StringRes title: Int? = null, @DrawableRes image: Int? = null, wave: Boolean = false,
        @StringRes info: Int? = null, @DrawableRes infoIcon: Int = R.drawable.ic_chevron_right, top: Int = 14
    ): LinearLayout {
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
            clipToOutline = true
            root.withTopMargin(this, top)
            if (title != null) addView(row().apply {
                if (image != null) addView(icon(image, 34).apply {
                    background = rounded(tile, 18)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(9) })
                addView(value(22f).apply {
                    setText(title)
                    ViewCompat.setAccessibilityHeading(this, true)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (info != null) addView(infoButton(title, info, infoIcon))
            })
        }
    }

    private fun metric(parent: LinearLayout, @StringRes title: Int, size: Float, top: Int = 0): TextView =
        value(size).apply {
            parent.withTopMargin(this, top)
            parent.addView(label(13f).apply { setText(title) })
        }

    private fun detail(
        parent: LinearLayout, @DrawableRes icon: Int, @StringRes title: Int, rank: Boolean = false, @StringRes info: Int? = null
    ): Pair<TextView, TextView> {
        val line = row().apply {
            background = if (rank) rounded(tile, 13) else rounded(context.getColor(R.color.stats_surface), 13, border)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            addView(icon(icon, 40).apply {
                background = rounded(if (rank) context.getColor(R.color.stats_surface) else tile, if (rank) 22 else 12)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
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
        if (info != null) line.addView(infoButton(title, info, R.drawable.ic_chevron_right))
        parent.withTopMargin(line, 8)
        return detail to badge
    }

    private fun infoButton(@StringRes title: Int, @StringRes info: Int, @DrawableRes image: Int) =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            addView(icon(image, 28).apply {
                background = rounded(tile, 15)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            contentDescription = context.getString(R.string.stats_about_metric, context.getString(title))
            isFocusable = true
            setOnClickListener {
                AlertDialog.Builder(context).setTitle(title).setMessage(info)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
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
