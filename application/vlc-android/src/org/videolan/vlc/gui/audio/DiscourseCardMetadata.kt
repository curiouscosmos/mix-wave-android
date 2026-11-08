package org.videolan.vlc.gui.audio

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

internal fun discourseCardLocation(value: String?) = value?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

private val cardDateOutput = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH)
private val cardDateInputs = listOf("MMM d, uuuu", "d MMMM, uuuu", "d MMMM uuuu").map {
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
        .withResolverStyle(java.time.format.ResolverStyle.STRICT)
}

internal fun discourseCardDate(value: String?): String? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // ISO timestamps describe the discourse's calendar date; do not shift it to the device's timezone.
    val iso = Regex("^\\d{4}-\\d{2}-\\d{2}(?:$|[T ].*)").matches(text)
    val normalized = text.replace(Regex("(\\d{1,2})(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE), "$1")
    for (format in if (iso) listOf(DateTimeFormatter.ISO_LOCAL_DATE) else cardDateInputs) {
        try {
            return LocalDate.parse(if (iso) text.take(10) else normalized, format).format(cardDateOutput)
        } catch (_: DateTimeParseException) { }
    }
    return null
}

internal fun View.bindDiscourseCardMetadata(item: Discourse, counts: String) {
    findViewById<TextView>(R.id.discourse_language).text = item.language.replaceFirstChar(Char::uppercase)
    findViewById<TextView>(R.id.discourse_counts).text = counts
    findViewById<TextView>(R.id.discourse_location).apply {
        text = discourseCardLocation(item.location)
        isVisible = !text.isNullOrEmpty()
    }
    val date = discourseCardDate(item.date)
    findViewById<TextView>(R.id.discourse_date).apply {
        text = date
        isVisible = date != null
    }
    findViewById<View>(R.id.discourse_date_separator).isVisible = date != null
}
