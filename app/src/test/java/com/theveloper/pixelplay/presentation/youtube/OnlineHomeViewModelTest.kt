package com.theveloper.pixelplay.presentation.youtube

import com.theveloper.pixelplay.data.youtube.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineHomeViewModelTest {
    private val repository = mockk<YouTubeRepository>(relaxed = true)
    private val home = mockk<HomeFeedCoordinator>(relaxed = true)
    private val song = OnlineTrack("aaaaaaaaaaa", "Song", "Artist", null)
    private val feed = OnlineHomeFeed(tracks = listOf(song))
    @Before fun prepare() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { repository.account } returns MutableStateFlow<MusicAccount?>(null)
        every { home.state } returns MutableStateFlow(HomeFeedUiState(feed = feed, isInitialLoading = false))
    }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun model() = OnlineHomeViewModel(repository, home, mockk(relaxed = true), mockk(relaxed = true))
    @Test fun searchNeverReadsHomeCacheOrChangesHomeState() = runTest {
        coEvery { repository.search("Song") } returns listOf(song)
        val vm = model()
        assertTrue(vm.state.value.tracks.isEmpty())
        vm.search("Song"); advanceUntilIdle()
        assertEquals(listOf(song), vm.state.value.tracks)
        assertEquals(feed, vm.homeState.value.feed)
        coVerify(exactly = 0) { repository.cachedHomeFeed() }
        coVerify(exactly = 0) { repository.homeFeed() }
        vm.search("")
        assertTrue(vm.state.value.tracks.isEmpty())
        assertEquals(feed, vm.homeState.value.feed)
    }
    @Test fun typingIsLoadingBeforeDebounceAndOnlyLatestQueryRuns() = runTest {
        val vm = model()
        coEvery { repository.search("Song") } returns listOf(song)
        vm.search("So", debounceMs = 400)
        assertTrue(vm.state.value.loading)
        assertEquals("So", vm.state.value.query)
        runCurrent(); advanceTimeBy(200)
        vm.search("Song", debounceMs = 400)
        runCurrent(); advanceTimeBy(399)
        assertTrue(vm.state.value.loading)
        coVerify(exactly = 0) { repository.search(any()) }
        advanceUntilIdle()
        assertEquals(listOf(song), vm.state.value.tracks)
        assertFalse(vm.state.value.loading)
        coVerify(exactly = 0) { repository.search("So") }
        vm.search("Other", debounceMs = 400)
        vm.search(""); advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.tracks.isEmpty())
        coVerify(exactly = 0) { repository.search("Other") }
    }
    @Test fun categoryChangesCancelOldRequestAndKeepCategoryIdentity() = runTest {
        val albums = com.theveloper.pixelplay.data.model.SearchFilterType.ALBUMS
        val artists = com.theveloper.pixelplay.data.model.SearchFilterType.ARTISTS
        val artist = OnlineSearchCollection(artists, HomeFeedItem("Artist", browseId = "UCartist"))
        coEvery { repository.searchCatalog("Query", albums) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(1_000) }
            OnlineSearchResults(collections = listOf(OnlineSearchCollection(albums, HomeFeedItem("Album", browseId = "MPREalbum"))))
        }
        coEvery { repository.searchCatalog("Query", artists) } returns OnlineSearchResults(collections = listOf(artist))
        val vm = model()
        vm.search("Query", filter = albums); runCurrent()
        vm.search("Query", filter = artists)
        assertTrue(vm.state.value.loading)
        assertEquals(artists, vm.state.value.filter)
        advanceUntilIdle()
        assertEquals(listOf(artist), vm.state.value.collections)
        assertEquals(artists, vm.state.value.filter)
        assertTrue(vm.state.value.tracks.isEmpty())
    }
    @Test fun allUsesMixedCatalogAndRetryPreservesSelectedFilter() = runTest {
        val all = com.theveloper.pixelplay.data.model.SearchFilterType.ALL
        val playlist = OnlineSearchCollection(com.theveloper.pixelplay.data.model.SearchFilterType.PLAYLISTS,
            HomeFeedItem("Playlist", browseId = "VLplaylist"))
        coEvery { repository.searchCatalog("Query", all) } throws java.io.IOException()
        val vm = model()
        vm.search("Query", filter = all); advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertEquals(all, vm.state.value.filter)
        coEvery { repository.searchCatalog("Query", all) } returns OnlineSearchResults(listOf(song), listOf(playlist))
        vm.search("Query", filter = all); advanceUntilIdle()
        assertEquals(listOf(song), vm.state.value.tracks)
        assertEquals(listOf(playlist), vm.state.value.collections)
        assertNull(vm.state.value.error)
    }
    @Test fun homeActionsDelegateWithoutCancellingSearch() = runTest {
        coEvery { repository.search("Song") } coAnswers { delay(100); listOf(song) }
        val vm = model()
        vm.search("Song")
        vm.refreshHomeOnEntry(); vm.refreshHomeNow(); vm.loadMoreHome(); vm.leaveHome()
        advanceUntilIdle()
        verify { home.enter(); home.refresh(true); home.loadMore(); home.leave() }
        assertEquals(listOf(song),vm.state.value.tracks)
    }
    @Test fun collectionFailureCanBeRetriedWithoutChangingHome() = runTest {
        val item = HomeFeedItem("Mix",browseId = "VLmix")
        val vm = model()
        coEvery { repository.browseCollection(item, null) } throws IllegalStateException()
        vm.openCollection(item); advanceUntilIdle()
        assertNotNull(vm.collection.value?.error)
        assertEquals(feed,vm.homeState.value.feed)
        coEvery { repository.browseCollection(item, null) } returns feed
        vm.openCollection(item); advanceUntilIdle()
        assertEquals(feed,vm.collection.value?.feed)
        assertNull(vm.collection.value?.error)
    }
    @Test fun loadMoreRetryRetainsSongsAndClosingCancelsLateResponse() = runTest {
        val item = HomeFeedItem("Mix",browseId = "VLmix")
        coEvery { repository.browseCollection(item, null) } returns feed.copy(continuation = "next")
        val vm = model()
        vm.openCollection(item); advanceUntilIdle()
        coEvery { repository.browseCollection(item, "next") } throws IllegalStateException()
        vm.loadMoreCollection(); advanceUntilIdle()
        assertEquals(feed.tracks, vm.collection.value?.feed?.tracks)
        coEvery { repository.browseCollection(item, "next") } returns OnlineHomeFeed(tracks = listOf(song.copy(videoId = "bbbbbbbbbbb")))
        vm.loadMoreCollection(); advanceUntilIdle()
        assertEquals(2,vm.collection.value?.feed?.tracks?.size)
        assertNull(vm.collection.value?.error)
        coEvery { repository.browseCollection(item, null) } coAnswers { delay(1000); feed }
        vm.openCollection(item); runCurrent(); vm.closeCollection(); advanceUntilIdle()
        assertNull(vm.collection.value)
    }
}
