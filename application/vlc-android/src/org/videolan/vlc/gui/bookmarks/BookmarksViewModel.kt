package org.videolan.vlc.gui.bookmarks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.videolan.vlc.bookmarks.BookmarkStore
import org.videolan.vlc.bookmarks.BookmarkedTrack

class BookmarksViewModel(application: Application) : AndroidViewModel(application) {
    private val store = BookmarkStore.shared(application)
    private val loadedTracks = MutableStateFlow<List<BookmarkedTrack>>(emptyList())
    val tracks = loadedTracks.asStateFlow()
    private val loading = MutableStateFlow(false)
    val isLoading = loading.asStateFlow()
    private val hasMore = MutableStateFlow(false)
    val hasMoreTracks = hasMore.asStateFlow()
    private var loadedCount = 0

    init {
        viewModelScope.launch {
            store.state.collect { state ->
                val keep = if (loadedCount == 0) state.size.coerceAtMost(BookmarkStore.PAGE_SIZE)
                    else loadedCount.coerceAtMost(state.size)
                loadedCount = keep
                loadedTracks.value = store.page(0, keep)
                hasMore.value = loadedCount < state.size
            }
        }
    }

    fun loadNextPage() {
        if (loading.value) return
        val total = store.count()
        if (loadedCount >= total) {
            hasMore.value = false
            return
        }
        loading.value = true
        try {
            val page = store.page(loadedCount, BookmarkStore.PAGE_SIZE)
            if (page.isNotEmpty()) {
                loadedCount += page.size
                loadedTracks.value = loadedTracks.value + page
            }
            hasMore.value = loadedCount < total
        } finally {
            loading.value = false
        }
    }

    fun remove(trackKey: String, positionMs: Long) {
        store.remove(trackKey, positionMs)
    }
}
