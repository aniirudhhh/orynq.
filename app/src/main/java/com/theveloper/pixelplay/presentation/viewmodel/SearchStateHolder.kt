package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.repository.MusicRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview

/**
 * Manages search state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Search query execution
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
    )

    // Search State
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null
    private val historyMutex = Mutex()

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            searchRequests
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request ->
                    val normalizedQuery = request.query

                    if (normalizedQuery.isBlank()) {
                        if (_searchResults.value.isNotEmpty()) {
                            _searchResults.value = persistentListOf()
                        }
                        return@collectLatest
                    }

                    try {
                        val currentFilter = _selectedSearchFilter.value
                        musicRepository.searchAll(normalizedQuery, currentFilter).collect { resultsList ->
                            // Sort: prioritize Song/Album matches over Artist/Playlist matches
                            val sortedResults = resultsList.sortedWith(
                                compareBy { result ->
                                    when (result) {
                                        is SearchResultItem.SongItem -> 0
                                        is SearchResultItem.AlbumItem -> 1
                                        is SearchResultItem.ArtistItem -> 2
                                        is SearchResultItem.PlaylistItem -> 3
                                    }
                                }
                            )

                            if (request.requestId != latestSearchRequestId.get()) {
                                return@collect
                            }

                            val immutableResults = sortedResults.toImmutableList()
                            if (_searchResults.value != immutableResults) {
                                _searchResults.value = immutableResults
                            }
                        }
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing search for query: $normalizedQuery")
                            _searchResults.value = persistentListOf()
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    private fun historyOperation(message: String, operation: suspend () -> Unit) {
        scope?.launch {
            historyMutex.withLock {
                try {
                    operation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, message)
                }
            }
        }
    }

    private suspend fun refreshHistory(limit: Int = 20) {
        _searchHistory.value = withContext(Dispatchers.IO) {
            musicRepository.getRecentSearchHistory(limit).toImmutableList()
        }
    }

    fun loadSearchHistory(limit: Int = 20) = historyOperation("Error loading search history") {
        refreshHistory(limit)
    }

    fun onSearchQuerySubmitted(query: String) {
        if (query.isBlank()) return
        historyOperation("Error adding search history item") {
            withContext(Dispatchers.IO) { musicRepository.addSearchHistoryItem(query) }
            refreshHistory()
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()

        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            if (_searchResults.value.isNotEmpty()) {
                _searchResults.value = persistentListOf()
            }
        }

        searchRequests.tryEmit(SearchRequest(normalizedQuery, requestId))
    }

    fun deleteSearchHistoryItem(query: String) = historyOperation("Error deleting search history") {
        withContext(Dispatchers.IO) { musicRepository.deleteSearchHistoryItemByQuery(query) }
        refreshHistory()
    }

    fun clearSearchHistory() = historyOperation("Error clearing search history") {
        withContext(Dispatchers.IO) { musicRepository.clearSearchHistory() }
        _searchHistory.value = persistentListOf()
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
