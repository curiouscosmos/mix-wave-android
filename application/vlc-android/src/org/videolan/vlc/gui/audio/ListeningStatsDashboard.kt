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

    private val todayBody = column()
    private val summaryBody = column()
    private var content = todayBody
    private val tabs: List<TextView>
    private val ring = ListeningStatsRing(context)
    private val heroDuration = headline(38).apply { gravity = Gravity.CENTER }
    private val heroTracks: TextView
    private val heroDiscourses: TextView
    private val dayComparison = label(15f).apply { gravity = Gravity.CENTER }
    private val summaryPeriod = label(17f).apply { setTextColor(android.graphics.Color.WHITE) }
    private val summaryDuration = headline(39).apply { setTextColor(android.graphics.Color.WHITE) }
    private val weekComparison = label(14f).apply { setTextColor(android.graphics.Color.WHITE) }
    private val dayBars = ListeningStatsChart(context, heatmap = false, chartDuration)
    private val weekBars = ListeningStatsChart(context, heatmap = false, chartDuration)
    private val downloaded: TextView
    private val clipTotal: TextView
    private val topTrack: Pair<TextView, TextView>
    private val topDiscourse: Pair<TextView, TextView>
    private val clipNote: TextView
    private val busiest: TextView
    private val repeats: TextView
    private val todayClips: TextView
    private val streakDays: List<TextView>
    private val streak: TextView
    private val milestone: TextView

    init {
        val segmented = row().apply {
            background = rounded(tile, 28, border)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        root.withTopMargin(segmented, 8)
        tabs = periods.mapIndexed { index, title ->
            label(14f).apply {
                setText(title)
                gravity = Gravity.CENTER
                minHeight = dp(42)
                isFocusable = true
                segmented.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener {
                    period = index
                    updateOverview()
                    (root.parent as? android.widget.ScrollView)?.scrollTo(0, 0)
                }
            }
        }

        root.addView(todayBody)
        root.addView(summaryBody)
        val dial = FrameLayout(context).apply {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.discourse_card_wave)
                imageTintList = ColorStateList.valueOf(accent)
                alpha = .46f
                scaleType = ImageView.ScaleType.FIT_XY
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, dp(90), Gravity.BOTTOM))
            addView(ring, FrameLayout.LayoutParams(-1, -1))
            addView(column().apply {
                gravity = Gravity.CENTER
                addView(icon(R.drawable.ic_search_headphones, 38))
                withTopMargin(value(17f).apply {
                    setText(R.string.listening_stats_time)
                    setTextColor(accent)
                    gravity = Gravity.CENTER
                }, 8)
                addView(heroDuration, LinearLayout.LayoutParams(-1, dp(58)))
                addView(label(16f).apply { setText(R.string.listening_stats_today) })
            }, FrameLayout.LayoutParams(dp(218), -1, Gravity.CENTER))
        }
        todayBody.withTopMargin(dial, 24, (context.resources.configuration.screenWidthDp * .65f).toInt().coerceIn(220, 310))
        todayBody.withTopMargin(dayComparison, 16)
        val totals = row()
        todayBody.withTopMargin(totals, 21)
        heroTracks = tileMetric(totals, 0, R.string.stats_tracks_lifetime, R.drawable.ic_discourse_tracks)
        heroDiscourses = tileMetric(totals, 1, R.string.stats_discourses_lifetime, R.drawable.ic_player_discourse)
        panel(R.string.stats_activity_heading, info = R.string.stats_chart_info).withTopMargin(dayBars, 4, 146)
        val highlights = panel(R.string.stats_highlights, R.drawable.ic_information)
        busiest = detail(highlights, R.drawable.ic_search_headphones, R.string.listening_stats_busiest_day).first
        repeats = detail(highlights, R.drawable.ic_repeat, R.string.stats_repeat_listening).first
        todayClips = detail(highlights, R.drawable.ic_player_clip, R.string.listening_stats_clip_count).first

        content = summaryBody
        val banner = FrameLayout(context).apply {
            background = rounded(accent, 22)
            clipToOutline = true
            addView(ListeningStatsSunset(context), FrameLayout.LayoutParams(-1, -1))
            addView(column().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(72), 0)
                addView(summaryPeriod)
                addView(summaryDuration, LinearLayout.LayoutParams(-1, dp(58)))
                addView(weekComparison)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        summaryBody.withTopMargin(banner, 19, 148)
        panel(R.string.listening_stats_time, R.drawable.ic_sort_number,
            info = R.string.stats_week_chart_info).withTopMargin(weekBars, 2, 160)
        val totalsRow = row()
        summaryBody.withTopMargin(totalsRow, 14)
        downloaded = tileMetric(totalsRow, 0, R.string.listening_stats_download_section, R.drawable.ic_discourse_download, trailingIcon = true)
        clipTotal = tileMetric(totalsRow, 1, R.string.listening_stats_clips, R.drawable.ic_player_clip, trailingIcon = true)
        val top = panel(R.string.stats_top_content, R.drawable.ic_discourse_tracks, info = R.string.stats_clips_info)
        topTrack = detail(top, R.drawable.ic_player_play_solid, R.string.listening_stats_clip_track, rank = true)
        topDiscourse = detail(top, R.drawable.ic_discourse_tracks, R.string.listening_stats_clip_discourse, rank = true)
        clipNote = label(12f).apply {
            setText(R.string.listening_stats_clip_legacy)
            isVisible = false
            top.withTopMargin(this, 6)
        }
        val streakPanel = panel(R.string.stats_streak_heading, R.drawable.ic_discourse_calendar,
            info = R.string.stats_streak_info, infoIcon = R.drawable.ic_information)
        val days = row()
        streakPanel.withTopMargin(days, 14)
        streakDays = java.time.DayOfWeek.values().map { day ->
            val cell = column().apply { gravity = Gravity.CENTER }
            days.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            val marker = value(19f).apply { gravity = Gravity.CENTER }
            cell.addView(marker, LinearLayout.LayoutParams(dp(29), dp(29)))
            cell.withTopMargin(label(12f).apply {
                gravity = Gravity.CENTER
                text = day.getDisplayName(java.time.format.TextStyle.SHORT, context.resources.configuration.locales[0])
            }, 4)
            marker
        }
        val milestones = row().apply {
            background = rounded(tile, 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        streakPanel.withTopMargin(milestones, 15)
        val current = column()
        val next = column()
        milestones.addView(current, weighted(0))
        milestones.addView(View(context).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(dp(1), dp(36)))
        milestones.addView(next, weighted(1))
        streak = metric(current, R.string.stats_current_streak, 19f)
        milestone = metric(next, R.string.stats_next_milestone, 19f)
        updateOverview()
    }

    fun bindTime(trend: ListeningStatsTrend, busiest: String, streak: String) {
        this.trend = trend
        updateOverview()
        dayBars.bind(trend)
        weekBars.bind(trend, calendarWeek = true)
        this.busiest.text = busiest
        this.streak.text = streak
        val next = ((trend.streak / 7) + 1) * 7
        milestone.text = context.resources.getQuantityString(R.plurals.stats_streak_days, next, next)
        streakDays.forEachIndexed { index, marker ->
            val active = trend.calendarWeek[index] > 0
            marker.text = if (active) "✓" else ""
            marker.setTextColor(context.getColor(R.color.stats_on_accent))
            marker.background = rounded(if (active) accent else context.getColor(R.color.stats_surface), 20, border)
            marker.contentDescription = context.getString(if (active) R.string.stats_listened_day else R.string.stats_empty_day)
        }
    }

    fun showCalendar() {
        val graph = ListeningStatsChart(context, heatmap = true, chartDuration).apply {
            trend?.let { bind(it) }
        }
        AlertDialog.Builder(context).setTitle(R.string.stats_calendar).setView(column().apply {
            setPadding(dp(18), dp(16), dp(18), dp(10))
            addView(graph, LinearLayout.LayoutParams(-1, -2))
        }).setPositiveButton(android.R.string.ok, null).show()
    }

    fun bindLibrary(counts: List<String>, repeatedTracks: Int) {
        heroTracks.text = counts[0]
        heroDiscourses.text = counts[1]
        repeats.text = context.resources.getQuantityString(R.plurals.stats_track_quantity, repeatedTracks, repeatedTracks)
    }

    fun bindClips(total: String, track: String, trackCount: String?, discourse: String, discourseCount: String?, legacy: Boolean) {
        clipTotal.text = total
        todayClips.text = total
        listOf(topTrack to (track to trackCount), topDiscourse to (discourse to discourseCount)).forEach { (views, data) ->
            views.first.text = data.first
            views.second.isVisible = data.second != null
            views.second.text = data.second
        }
        clipNote.isVisible = legacy
    }

    fun bindDownloads(count: String) { downloaded.text = count }

    fun timeError(error: String) {
        heroDuration.text = error
        summaryDuration.text = error
        busiest.text = error
    }

    fun libraryError(error: String) {
        heroTracks.text = error
        heroDiscourses.text = error
        repeats.text = error
    }

    fun clipsError(error: String) = bindClips(error, error, null, error, null, false)

    private fun updateOverview() {
        todayBody.isVisible = period == 0
        summaryBody.isVisible = period != 0
        tabs.forEachIndexed { index, tab ->
            tab.isSelected = index == period
            tab.setTextColor(if (tab.isSelected) context.getColor(R.color.stats_on_accent) else primaryTextColor)
            tab.background = if (tab.isSelected) rounded(accent, 25) else null
        }
        summaryPeriod.setText(windows[period])
        val data = trend ?: return
        val totals = data.time
        val selected = listOf(totals.today, totals.week, totals.month, totals.year, totals.overall)[period]
        if (period == 0) {
            heroDuration.text = chartDuration(selected)
            heroDuration.contentDescription = duration(selected)
            val peak = data.periodPeaks[0]
            ring.fraction = if (peak > 0) (selected.toDouble() / peak).toFloat().coerceIn(0f, 1f) else 0f
            setComparison(dayComparison, selected, data.yesterday, R.string.stats_day_comparison)
        } else {
            summaryDuration.text = chartDuration(selected)
            summaryDuration.contentDescription = duration(selected)
            weekComparison.isVisible = period == 1
            if (period == 1) setComparison(weekComparison, selected, data.previousWeek, R.string.stats_week_comparison)
        }
    }

    private fun setComparison(view: TextView, current: Long, previous: Long, @StringRes message: Int) {
        if (previous <= 0) {
            view.setText(R.string.stats_comparison_unavailable)
            return
        }
        val delta = (current - previous).toDouble() / previous
        val percent = java.text.NumberFormat.getPercentInstance(context.resources.configuration.locales[0]).format(delta)
        val change = when {
            delta > 0 -> "↑ +$percent"
            delta < 0 -> "↓ $percent"
            else -> percent
        }
        val text = context.getString(message, change)
        val start = text.indexOf(change)
        view.text = android.text.SpannableString(text).apply {
            if (start >= 0 && delta > 0) {
                val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val color = if (view === weekComparison || dark) 0xFF99E6A5.toInt() else 0xFF186B36.toInt()
                setSpan(android.text.style.ForegroundColorSpan(color), start, start + change.length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, start + change.length, 0)
            }
        }
    }

    private fun headline(max: Int) = value(max.toFloat()).apply {
        maxLines = 1
        setText(R.string.loading)
        setAutoSizeTextTypeUniformWithConfiguration(14, max, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

    private fun tileMetric(parent: LinearLayout, index: Int, @StringRes title: Int, @DrawableRes image: Int,
                           trailingIcon: Boolean = false): TextView {
        val tileView = row().apply {
            background = rounded(context.getColor(R.color.stats_surface), 18, border)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        parent.addView(tileView, weighted(index))
        val metric = column()
        val imageView = icon(image, 42).apply {
            background = rounded(tile, 22)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        if (!trailingIcon) tileView.addView(imageView)
        tileView.addView(metric, LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (!trailingIcon) marginStart = dp(9)
        })
        if (trailingIcon) tileView.addView(imageView)
        return metric(metric, title, 30f)
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
            content.withTopMargin(this, top)
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

}
