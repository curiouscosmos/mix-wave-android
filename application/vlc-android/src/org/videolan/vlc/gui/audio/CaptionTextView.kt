package org.videolan.vlc.gui.audio

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import org.videolan.vlc.discourse.CaptionCue
import org.videolan.vlc.discourse.ClipRange

/** Cues flow as prose, while their spans retain timing and individual click targets. */
class CaptionTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatTextView(context, attrs) {
    private var cues = emptyList<CaptionCue>()
    private var ranges = emptyList<IntRange>()
    private val spoken = ForegroundColorSpan(Color.rgb(255, 110, 90))
    private val selectionSpans = ArrayList<BackgroundColorSpan>()
    var onCueClick: ((CaptionCue) -> Unit)? = null

    init {
        setTextColor(Color.WHITE)
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
    }

    fun setCues(value: List<CaptionCue>) {
        cues = value
        selectionSpans.clear()
        val content = SpannableStringBuilder()
        ranges = value.map { cue ->
            val phrase = cue.text.replace(Regex("\\s+"), " ").trim()
            if (content.isNotEmpty() && phrase.isNotEmpty()) content.append(' ')
            val start = content.length
            content.append(phrase)
            if (phrase.isNotEmpty()) content.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { onCueClick?.invoke(cue) }
                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
            }, start, content.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            start until content.length
        }
        setText(content, BufferType.SPANNABLE)
    }

    fun setActive(index: Int) {
        val content = text as? Spannable ?: return
        content.removeSpan(spoken)
        ranges.getOrNull(index)?.takeUnless { it.isEmpty() }?.let {
            content.setSpan(spoken, it.first, it.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun setClipRange(range: ClipRange?) {
        val content = text as? Spannable ?: return
        selectionSpans.forEach { content.removeSpan(it) }
        selectionSpans.clear()
        cues.forEachIndexed { index, cue ->
            if (range != null && cue.startMs < range.endMs && cue.endMs > range.startMs) {
                ranges[index].takeUnless { it.isEmpty() }?.let {
                    val span = BackgroundColorSpan(0x303F8CFF)
                    content.setSpan(span, it.first, it.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    selectionSpans += span
                }
            }
        }
    }

    fun cueTop(index: Int): Int? {
        val offset = ranges.getOrNull(index)?.first ?: return null
        val lines = layout ?: return null
        return totalPaddingTop + lines.getLineTop(lines.getLineForOffset(offset))
    }
}
