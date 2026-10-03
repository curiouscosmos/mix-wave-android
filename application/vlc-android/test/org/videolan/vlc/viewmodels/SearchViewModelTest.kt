package org.videolan.vlc.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.BaseTest
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.PageResponse
import org.videolan.vlc.discourse.PaginationMeta

class SearchViewModelTest : BaseTest() {
    @Test
    fun searchLoadsDiscoursesAndStopsAt24Results() {
        val requestedPages = mutableListOf<Int>()
        val model = SearchViewModel { page, query ->
            assertEquals("dhyan", query)
            requestedPages += page
            PageResponse(
                (1..16).map { discourse("$page-$it") },
                PaginationMeta(page, 16, 40, 3)
            )
        }

        model.search(" dhyan ")
        model.loadMore()
        model.loadMore()

        val state = model.state.value as SearchViewModel.State.Results
        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(24, state.discourses.size)
        assertEquals("2-8", state.discourses.last().id)
    }

    @Test
    fun blankSearchDoesNotCallLoader() {
        var calls = 0
        val model = SearchViewModel { _, _ ->
            calls++
            error("blank query should not load")
        }

        model.search("   ")

        assertEquals(0, calls)
        assertTrue(model.state.value is SearchViewModel.State.Idle)
    }

    private fun discourse(id: String) = Discourse(
        id = id,
        title = "Title $id",
        thumbnailUrl = null,
        isAudioCleaned = true,
        language = "hindi",
        slug = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z"
    )
}
