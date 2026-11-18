package org.videolan.vlc.gui.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import java.time.DayOfWeek
import java.time.format.TextStyle
import org.videolan.vlc.R
import kotlin.math.ceil

internal class ListeningStatsRing(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(11f)
    }
    private val bounds = RectF()
    private val accent = context.getColor(R.color.stats_accent)
    private val tile = context.getColor(R.color.stats_border)
    var fraction = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val radius = (minOf(width, height) - paint.strokeWidth) / 2
        val x = width / 2f
        val y = height / 2f
        bounds.set(x - radius, y - radius, x + radius, y + radius)
        paint.color = tile
        canvas.drawOval(bounds, paint)
        if (fraction > 0f) {
            paint.color = ColorUtils.blendARGB(tile, accent, .54f)
            canvas.drawArc(bounds, -90f, fraction * 360f, false, paint)
            paint.color = accent
            canvas.drawArc(bounds, -90f, fraction * 270f, false, paint)
        }
    }
}

internal class ListeningStatsChart(
    context: Context,
    private val heatmap: Boolean,
    private val formatDuration: (Long) -> String
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.getColor(R.color.stats_accent)
    private val muted = context.getColor(R.color.stats_accent_muted)
    private val border = context.getColor(R.color.stats_border)
    private val tile = context.getColor(R.color.stats_tile)
    private val text = context.getColor(R.color.stats_secondary)
    private val onAccent = context.getColor(R.color.stats_on_accent)
    private val locale get() = resources.configuration.locales[0]
    private var trend: ListeningStatsTrend? = null
    private var calendarWeek = false
    private fun values(trend: ListeningStatsTrend) = if (calendarWeek) trend.calendarWeek else trend.lastWeek
    private val weekdays get() = DayOfWeek.values().map { it.getDisplayName(TextStyle.SHORT, locale) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!heatmap) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        paint.textSize = sp(11f)
        val column = (width - weekdays.maxOf(paint::measureText) - dp(5f)) / 14
        setMeasuredDimension(width, resolveSize((column * 7 + dp(27f)).toInt(), heightMeasureSpec))
    }

    fun bind(trend: ListeningStatsTrend, calendarWeek: Boolean = false) {
        this.trend = trend
        this.calendarWeek = calendarWeek
        contentDescription = if (heatmap) context.getString(R.string.stats_heatmap_description)
        else values(trend).mapIndexed { index, duration -> "${label(trend, index)}: ${formatDuration(duration)}" }.joinToString(". ")
        invalidate()
    }

    private fun label(trend: ListeningStatsTrend, index: Int): String =
        if (calendarWeek) weekdays[index]
        else if (index == 6) context.getString(R.string.listening_stats_today)
        else trend.today.minusDays(6L - index).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    override fun onDraw(canvas: Canvas) {
        val data = trend ?: return
        if (heatmap) drawActivity(canvas, data) else drawBars(canvas, data)
    }

    private fun drawBars(canvas: Canvas, data: ListeningStatsTrend) {
        paint.textSize = sp(11f)
        val values = values(data)
        val peak = values.maxOrNull() ?: 0
        val interval = if (peak <= 7_200_000) 1_800_000L else 3_600_000L
        val step = (ceil(peak / (4.0 * interval)).toLong().coerceAtLeast(1) * interval)
        val scale = 4 * step
        val yLabels = (0..4).map { formatDuration(it * step) }
        val left = if (calendarWeek) yLabels.maxOf(paint::measureText) + dp(9f) else 0f
        val top = dp(24f)
        val bottom = height - dp(21f)
        val column = (width - left) / 7f
        if (column <= 0f || bottom <= top) return
        if (calendarWeek) for (index in 0..4) {
            val y = bottom - (bottom - top) * index / 4f
            paint.color = border
            canvas.drawLine(left, y, width.toFloat(), y, paint)
            paint.color = text
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(yLabels[index], left - dp(5f), y + dp(3f), paint)
        }
        values.forEachIndexed { index, millis ->
            val x = left + column * (index + 0.5f)
            val barHeight = if (millis > 0) ((bottom - top) * millis / scale).coerceAtLeast(dp(2f)) else 0f
            val y = bottom - barHeight
            if (barHeight > 0f) {
                val selected = !calendarWeek && index == 6
                paint.shader = LinearGradient(0f, y, 0f, bottom,
                    if (selected) accent else ColorUtils.blendARGB(tile, muted, .63f),
                    if (selected) ColorUtils.blendARGB(accent, text, .22f) else muted, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(x - column * .39f, y, x + column * .39f, bottom, dp(7f), dp(7f), paint)
                paint.shader = null
            }
            paint.color = text
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(label(data, index), x, bottom + dp(16f), paint)
            if (!calendarWeek && index == 6 && millis > 0) {
                val caption = formatDuration(millis)
                val bubbleWidth = paint.measureText(caption) + dp(12f)
                val edge = minOf(width.toFloat(), x + bubbleWidth / 2)
                paint.color = accent
                canvas.drawRoundRect(edge - bubbleWidth, y - dp(22f), edge, y - dp(4f), dp(6f), dp(6f), paint)
                paint.color = onAccent
                canvas.drawText(caption, edge - bubbleWidth / 2, y - dp(9f), paint)
            }
        }
    }

    private fun drawActivity(canvas: Canvas, data: ListeningStatsTrend) {
        paint.textSize = sp(11f)
        paint.textAlign = Paint.Align.LEFT
        val days = weekdays
        val left = days.maxOf(paint::measureText) + dp(8f)
        val gap = dp(3f)
        val column = (width - left + gap) / 14
        val row = minOf(column, (height - dp(26f)) / 7)
        if (column <= gap || row <= gap) return
        val peak = data.activity.filterNotNull().maxOrNull()?.coerceAtLeast(1) ?: 1
        days.forEachIndexed { index, label ->
            paint.color = text
            canvas.drawText(label, 0f, index * row + (row - gap - paint.ascent() - paint.descent()) / 2, paint)
        }
        data.activity.forEachIndexed { index, millis ->
            if (millis != null) {
                paint.color = intensity(if (millis == 0L) 0f else (.25f + .75f * millis / peak))
                val x = left + index / 7 * column
                val y = index % 7 * row
                canvas.drawRoundRect(x, y, x + column - gap, y + row - gap, dp(3f), dp(3f), paint)
            }
        }
        val more = context.getString(R.string.stats_more)
        val less = context.getString(R.string.stats_less)
        val end = width - paint.measureText(more)
        val y = 7 * row + dp(9f)
        val legendSize = dp(10f)
        val legendStep = legendSize + gap
        val start = end - gap - 5 * legendStep
        paint.color = text
        canvas.drawText(less, start - paint.measureText(less) - gap, y + legendSize, paint)
        canvas.drawText(more, end, y + legendSize, paint)
        repeat(5) { level ->
            paint.color = intensity(level / 4f)
            val x = start + level * legendStep
            canvas.drawRoundRect(x, y, x + legendSize, y + legendSize, dp(3f), dp(3f), paint)
        }
    }

    private fun intensity(fraction: Float) = ColorUtils.blendARGB(tile, accent, fraction)

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}

private fun View.dp(value: Float) = value * resources.displayMetrics.density


internal class ListeningStatsSunset(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ridge = Path()
    private var sky: Shader? = null
    private var glow: Shader? = null
    private val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val colors = if (dark) intArrayOf(0xFF67364D.toInt(), 0xFF4D293E.toInt(), 0xFF291D31.toInt())
        else intArrayOf(0xFFAE5C68.toInt(), 0xFF78364F.toInt(), 0xFF3B263C.toInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        sky = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF60384F.toInt(), 0xFF9A4C62.toInt(), 0xFFD58281.toInt()), null, Shader.TileMode.CLAMP)
        glow = RadialGradient(w * .83f, h * .52f, h * .63f,
            intArrayOf(0xFFFFD4A3.toInt(), 0x00FFB28C), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = sky
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = glow
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFE2AD.toInt()
        canvas.drawCircle(width * .83f, height * .52f, height * .15f, paint)
        for (index in colors.indices) {
            val offset = index * .092f
            ridge.reset()
            ridge.moveTo(width * .24f, height.toFloat())
            ridge.lineTo(width * .53f, height * (.91f + offset))
            ridge.lineTo(width * .76f, height * (.61f + offset))
            ridge.lineTo(width * .86f, height * (.65f + offset))
            ridge.lineTo(width * .97f, height * (.45f + offset))
            ridge.lineTo(width.toFloat(), height * (.49f + offset))
            ridge.lineTo(width.toFloat(), height.toFloat())
            ridge.close()
            paint.color = colors[index]
            canvas.drawPath(ridge, paint)
        }
    }
}
