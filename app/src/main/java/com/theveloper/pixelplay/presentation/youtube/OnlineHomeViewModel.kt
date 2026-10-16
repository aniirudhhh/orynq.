package com.theveloper.pixelplay.presentation.youtube

import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.youtube.OnlineSearchCollection
import com.theveloper.pixelplay.data.youtube.OnlineSearchResults
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.youtube.OnlineTrack
import com.theveloper.pixelplay.data.youtube.HomeFeedShelf
import com.theveloper.pixelplay.data.youtube.HomeFeedItem
import com.theveloper.pixelplay.data.youtube.OnlineHomeFeed
import com.theveloper.pixelplay.data.youtube.appendCollectionPage
import com.theveloper.pixelplay.data.youtube.YouTubeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnlineHomeState(
    val filter: SearchFilterType = SearchFilterType.SONGS,
    val collections: List<OnlineSearchCollection> = emptyList(),
    val tracks: List<OnlineTrack> = emptyList(), val loading: Boolean = true,
    val query: String = "", val error: String? = null,
    val shelves: List<HomeFeedShelf> = emptyList(),
    val continuation: String? = null, val loadingMore: Boolean = false, val moreError: String? = null,
)

data class OnlineCollectionState(val item: HomeFeedItem, val feed: OnlineHomeFeed = OnlineHomeFeed(),
    val loading: Boolean = true, val error: String? = null, val loadingMore: Boolean = false)

data class ConnectionState(val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class OnlineHomeViewModel @Inject constructor(
    private val repository: YouTubeRepository,
    private val home: com.theveloper.pixelplay.data.youtube.HomeFeedCoordinator,
    private val artworkPrefetcher: HomeArtworkPrefetcher,
    private val listeningSync: com.theveloper.pixelplay.data.youtube.YouTubeListeningSync,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnlineHomeState(loading = false))
    val state = mutableState.asStateFlow()
    val listeningSyncState = listeningSync.state
    fun setListeningSyncEnabled(enabled: Boolean) = listeningSync.setEnabled(enabled)
    val homeState = home.state
    fun refreshHomeOnEntry() = home.enter()
    fun leaveHome() = home.leave()
    fun refreshHomeNow() = home.refresh()
    fun loadMoreHome() = home.loadMore()
    fun homeViewport(atTop: Boolean, scrolling: Boolean, blocked: Boolean) = home.viewport(atTop, scrolling, blocked)
    fun homeTouching(value: Boolean) = home.touching(value)
    fun homeShelfScrolling(key: String, value: Boolean) = home.shelfScrolling(key, value)
    fun quickPickSeen(videoId: String) = home.seen(videoId)
    val account = repository.account
    private val mutableConnection = MutableStateFlow(ConnectionState())
    val connection = mutableConnection.asStateFlow()
    private var connectionRequest: Job? = null
    private var collectionRequest: Job? = null
    private val mutableCollection = MutableStateFlow<OnlineCollectionState?>(null)
    val collection = mutableCollection.asStateFlow()
    fun closeCollection() { collectionRequest?.cancel(); mutableCollection.value = null }
    fun openCollection(item: HomeFeedItem) {
        if (item.browseId == null && item.playlistId == null) return
        collectionRequest?.cancel()
        mutableCollection.value = OnlineCollectionState(item)
        collectionRequest = viewModelScope.launch {
            try { mutableCollection.value = OnlineCollectionState(item, repository.browseCollection(item), loading = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableCollection.value = OnlineCollectionState(item, loading = false,
                error = "Couldn't open this collection. Please try again.") }
        }
    }
    fun loadMoreCollection() {
        val current = mutableCollection.value ?: return
        val token = current.feed.continuation ?: return
        if (collectionRequest?.isActive == true) return
        mutableCollection.value = current.copy(loadingMore = true, error = null)
        collectionRequest = viewModelScope.launch {
            try {
                val page = repository.browseCollection(current.item, token)
                mutableCollection.value = current.copy(feed = appendCollectionPage(current.feed, page), error = null, loadingMore = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableCollection.value = current.copy(error = "Couldn't load more songs. Please try again.") }
        }
    }
    fun clearConnectionError() { mutableConnection.value = mutableConnection.value.copy(error = null) }
    fun connectionInputError() { mutableConnection.value = ConnectionState(error = "Could not read this session file. Choose a Cookie header or browser-session file under 64 KB.") }
    fun connectAccount(input: String, authUser: String) {
        connectionRequest?.cancel()
        connectionRequest = viewModelScope.launch {
            mutableConnection.value = ConnectionState(loading = true)
            try {
                repository.connectAccount(input, authUser)
                mutableConnection.value = ConnectionState()
                request?.cancel()
                closeCollection()
                mutableState.value = OnlineHomeState(loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableConnection.value = ConnectionState(error = "Connection failed. Check that the session belongs to a signed-in YouTube Music account and that the account index is correct, then retry.")
            }
        }
    }
    fun disconnectAccount() {
        connectionRequest?.cancel()
        request?.cancel()
        closeCollection()
        repository.disconnectAccount()
        mutableConnection.value = ConnectionState()
        mutableState.value = OnlineHomeState(loading = false)
    }
    val savedTracks = repository.savedTracks
    val recentSearches = repository.recentSearches
    fun clearRecentSearches() = repository.clearRecentSearches()
    fun search(query: String, debounceMs: Long = 0, filter: SearchFilterType = SearchFilterType.SONGS) {
        if (query.isBlank()) {
            request?.cancel()
            mutableState.value = OnlineHomeState(loading = false)
        } else load(query, debounceMs, filter)
    }
    fun toggleSaved(track: OnlineTrack) = repository.toggleSaved(track)
    private var request: Job? = null
    fun load(query: String = "", debounceMs: Long = 0, filter: SearchFilterType = SearchFilterType.SONGS) {
        if (query.isBlank()) { search(""); return }
        request?.cancel()
        mutableState.value = OnlineHomeState(loading = true, query = query.trim(), filter = filter)
        request = viewModelScope.launch {
            delay(debounceMs)
            repository.rememberSearch(query.trim())
            try {
                val results = if (filter == SearchFilterType.SONGS) OnlineSearchResults(tracks = repository.search(query.trim()))
                    else repository.searchCatalog(query.trim(), filter)
                coroutineContext.ensureActive()
                mutableState.value = OnlineHomeState(tracks = results.tracks, collections = results.collections, loading = false, query = query.trim(), filter = filter)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                timber.log.Timber.tag("OnlineSearch").w("Request failed: %s", error.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(loading = false,
                    error = "Couldn't reach YouTube Music. Check your connection and try again.")
            }
        }
    }
}
