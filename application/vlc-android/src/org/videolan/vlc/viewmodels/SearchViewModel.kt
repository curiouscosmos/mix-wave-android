package org.videolan.vlc.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseRepository
import org.videolan.vlc.discourse.PageResponse

class SearchViewModel(
    private val pageLoader: suspend (Int, String) -> PageResponse<Discourse>
) : ViewModel() {
    sealed class State {
        object Idle : State()
        object Loading : State()
        data class Results(val query: String, val discourses: List<Discourse>) : State()
        object Empty : State()
        object Error : State()
    }

    private val mutableState = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = mutableState
    private var loadJob: Job? = null
    private var query = ""
    private var nextPage = 1
    private var totalPages = 0
    private var results = emptyList<Discourse>()

    fun search(value: String) {
        val nextQuery = value.trim()
        if (nextQuery == query && (loadJob?.isActive == true || mutableState.value is State.Results)) return
        loadJob?.cancel()
        query = nextQuery
        results = emptyList()
        nextPage = 1
        totalPages = 0
        if (nextQuery.isEmpty()) {
            mutableState.value = State.Idle
            return
        }
        loadJob = viewModelScope.launch { loadFirstPage(nextQuery) }
    }

    fun loadMore() {
        if (query.isEmpty() || nextPage > totalPages || results.size >= MAX_RESULTS || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            try {
                val response = pageLoader(nextPage, query)
                nextPage++
                totalPages = response.meta.totalPages
                results = (results + response.data).take(MAX_RESULTS)
                mutableState.value = State.Results(query, results)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the current results visible; the next scroll retries this page.
            }
        }
    }

    private suspend fun loadFirstPage(search: String) {
        mutableState.value = State.Loading
        try {
            val response = pageLoader(1, search)
            nextPage = 2
            totalPages = response.meta.totalPages
            results = response.data.take(MAX_RESULTS)
            mutableState.value = if (results.isEmpty()) State.Empty else State.Results(search, results)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.value = State.Error
        }
    }

    class Factory(context: android.content.Context) : ViewModelProvider.Factory {
        private val repository = DiscourseRepository(context.applicationContext)

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return SearchViewModel { page, query -> repository.getDiscourses(page = page, search = query) } as T
        }
    }

    private companion object {
        const val MAX_RESULTS = 24
    }
}
