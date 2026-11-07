package org.videolan.vlc.gui.audio

import androidx.recyclerview.widget.DiffUtil
import org.videolan.vlc.discourse.Discourse

internal data class CatalogueRow(val discourse: Discourse, val heading: String?)

internal fun Discourse.cardLanguage() =
    listOfNotNull(language.replaceFirstChar(Char::uppercase), location?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" | ")

internal fun Discourse.cardMetadata(tracks: String) =
    listOfNotNull(tracks, date?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" | ")

internal fun catalogueRows(items: List<Discourse>, alphabetical: Boolean) = items.mapIndexed { index, item ->
    CatalogueRow(item, discourseCatalogueHeading(item.title, items.getOrNull(index - 1)?.title, alphabetical))
}

internal fun sameDiscourseCard(old: Discourse, new: Discourse) =
    old.title == new.title && old.language == new.language && old.thumbnailUrl == new.thumbnailUrl &&
        old.totalTracks == new.totalTracks && old.totalLikes == new.totalLikes &&
        old.location == new.location && old.date == new.date

internal object CatalogueDiff : DiffUtil.ItemCallback<CatalogueRow>() {
    override fun areItemsTheSame(old: CatalogueRow, new: CatalogueRow) = old.discourse.id == new.discourse.id
    override fun areContentsTheSame(old: CatalogueRow, new: CatalogueRow) =
        old.heading == new.heading && sameDiscourseCard(old.discourse, new.discourse)
}
