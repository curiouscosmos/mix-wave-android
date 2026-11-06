package org.videolan.vlc.gui.audio

import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.*
import org.junit.Test
import org.videolan.vlc.BaseTest
import org.videolan.vlc.discourse.Discourse
import java.util.concurrent.Executor

@org.robolectric.annotation.Config(sdk = [28])
class CatalogueRowTest : BaseTest() {
    private fun discourse(id: String, title: String = id) = Discourse(id, title, null, false, "hindi", null, "", "")

    @Test fun appendingAcrossAlphabeticalBoundaryOnlyInsertsNewRows() {
        val events = mutableListOf<String>()
        val callback = object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { events += "insert:$position:$count" }
            override fun onRemoved(position: Int, count: Int) { events += "remove:$position:$count" }
            override fun onMoved(fromPosition: Int, toPosition: Int) { events += "move" }
            override fun onChanged(position: Int, count: Int, payload: Any?) { events += "change:$position:$count" }
        }
        val direct = Executor { it.run() }
        val differ = AsyncListDiffer(callback, AsyncDifferConfig.Builder(CatalogueDiff)
            .setBackgroundThreadExecutor(direct).setMainThreadExecutor(direct).build())
        val first = listOf(discourse("a", "Apple"), discourse("b", "Apricot"))
        differ.submitList(catalogueRows(first, true))
        events.clear()
        differ.submitList(catalogueRows(first + listOf(discourse("c", "Avocado"), discourse("d", "Banana")), true))
        assertEquals(listOf("insert:2:2"), events)
        assertEquals(listOf("A", null, null, "B"), differ.currentList.map { it.heading })
        events.clear()
        differ.submitList(catalogueRows(differ.currentList.map { it.discourse.copy(updatedAt = "changed") }, true))
        assertTrue(events.isEmpty())
    }

    @Test fun filteringAndSortingRecalculateHeadingsAndDisplayedChangesRebind() {
        val a = discourse("a", "Apple")
        val b = discourse("b", "Apricot")
        val old = catalogueRows(listOf(a, b), true)
        val filtered = catalogueRows(listOf(b), true).single()
        assertEquals("A", filtered.heading)
        assertFalse(CatalogueDiff.areContentsTheSame(old[1], filtered))
        val sorted = catalogueRows(listOf(a, b), false)
        assertTrue(sorted.all { it.heading == null })
        assertFalse(CatalogueDiff.areContentsTheSame(old[0], sorted[0]))
        assertTrue(CatalogueDiff.areItemsTheSame(old[0], CatalogueRow(a.copy(totalLikes = 9), "A")))
        assertFalse(CatalogueDiff.areContentsTheSame(old[0], CatalogueRow(a.copy(totalLikes = 9), "A")))
    }

    @Test fun footerTransitionsOnlyNotifyWhenVisibilityChanges() {
        val footer = DiscourseFragment.LoadingFooter()
        val events = mutableListOf<String>()
        footer.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { events += "insert:$positionStart:$itemCount" }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { events += "remove:$positionStart:$itemCount" }
        })
        footer.setLoadingMore(true)
        assertEquals(1, footer.itemCount)
        footer.setLoadingMore(true)
        footer.setLoadingMore(false)
        footer.setLoadingMore(false)
        assertEquals(0, footer.itemCount)
        assertEquals(listOf("insert:0:1", "remove:0:1"), events)
    }
}
