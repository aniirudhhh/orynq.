package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository.PlaybackHistoryEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeFeedCoordinatorTest {
    private fun track(index: Int) = OnlineTrack(index.toString().padStart(11, '0'), "Song $index", "Artist", null)
    private fun feed(start: Int = 0, count: Int = 24, token: String? = null): OnlineHomeFeed {
        val tracks = (start until start + count).map(::track)
        return OnlineHomeFeed(listOf(HomeFeedShelf("picks", "Quick picks", HomeShelfLayout.ROWS,
            tracks.map { HomeFeedItem(it.title, track = it) })), tracks, token)
    }
    private class Cache(var record: HomeCacheRecord? = null) : HomeFeedCache {
        var fail = false
        var clears = 0
        override suspend fun read(namespace: String): HomeCacheRecord? {
            if (fail) error("Disk unavailable")
            return record?.takeIf { it.namespace == namespace }
        }
        override suspend fun write(record: HomeCacheRecord) { this.record = record.copy(active = record.active?.forDisk(), pending = record.pending?.forDisk()) }
        override suspend fun clear() { clears++; record = null }
    }
    private class Source : HomeFeedSource {
        override val namespace = MutableStateFlow("anonymous")
        override val historyChanges = MutableStateFlow(0L)
        var events = emptyList<PlaybackHistoryEntry>()
        var old: HomeFeedSnapshot? = null
        var first: suspend () -> OnlineHomeFeed = { error("Offline") }
        var next: suspend (String) -> OnlineHomeFeed = { error("Offline") }
        var art: suspend (OnlineHomeFeed) -> OnlineHomeFeed = { it }
        var calls = 0
        var pageCalls = 0
        override suspend fun history() = events
        override suspend fun legacy() = old
        override suspend fun firstPage(): OnlineHomeFeed { calls++; return first() }
        override suspend fun nextPage(token: String): OnlineHomeFeed { pageCalls++; return next(token) }
        override suspend fun enrich(feed: OnlineHomeFeed) = art(feed)
    }
    private inner class Harness(scope: CoroutineScope, stale: Boolean = false, cached: Boolean = true) {
        var time = 10_000_000L
        val source = Source()
        val original = feed(token = "next")
        val cache = Cache(if (cached) HomeCacheRecord("anonymous", active = HomeFeedSnapshot(listOf(original),
            time - if (stale) HomeFeedCoordinator.REFRESH_AGE else 0)) else null)
        val home = HomeFeedCoordinator(source, cache, scope) { time }
    }
    private fun TestScope.tick(h: Harness, millis: Long) { h.time += millis; advanceTimeBy(millis); runCurrent() }

    @Test fun staleCacheIsVisibleBeforeNetworkAndArtworkCannotDelayNewFeed() = runTest {
        val h = Harness(backgroundScope, stale = true)
        val response = CompletableDeferred<OnlineHomeFeed>()
        val art = CompletableDeferred<OnlineHomeFeed>()
        h.source.first = { response.await() }; h.source.art = { art.await() }
        runCurrent()
        assertEquals(h.original, h.home.state.value.feed)
        h.home.enter(); runCurrent()
        assertTrue(h.home.state.value.isBackgroundRefreshing)
        assertFalse(h.home.state.value.isPullRefreshing)
        assertFalse(h.home.state.value.isInitialLoading)
        response.complete(feed(40)); runCurrent()
        h.home.viewport(true, false); tick(h, 1_000)
        assertEquals(40, h.home.state.value.tracks.first().title.substringAfter(' ').toInt())
        assertFalse(h.home.state.value.isBackgroundRefreshing)
        assertFalse(art.isCompleted)
    }
    @Test fun freshCacheUsesNoRequestAndQuickTabSwitchingKeepsSelection() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter(); runCurrent()
        val selected = h.home.state.value.quickPicks
        selected.take(4).forEach { h.home.seen(it.videoId) }
        h.home.leave(); tick(h, 500); h.home.enter(); runCurrent()
        assertEquals(selected, h.home.state.value.quickPicks)
        assertEquals(0,h.source.calls)
    }
    @Test fun meaningfulGapRotatesExposedRowsButNeverDuringVisit() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter(); runCurrent()
        val initial = h.home.state.value.quickPicks.take(4).map { it.videoId }
        initial.forEach(h.home::seen)
        assertEquals(initial,h.home.state.value.quickPicks.take(4).map { it.videoId })
        h.home.leave(); tick(h,HomeFeedCoordinator.SESSION_GAP); h.home.enter(); runCurrent()
        assertTrue(h.home.state.value.quickPicks.take(4).none { it.videoId in initial })
    }
    @Test fun newFeedWaitsForTopAndAFullSecondWithoutTouchOrScroll() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter()
        h.source.first = { feed(40) }; h.home.refresh(); runCurrent()
        h.home.viewport(false, false); tick(h, 2_000)
        assertEquals(h.original,h.home.state.value.feed)
        h.home.viewport(true, false); h.home.touching(true); tick(h, 2_000)
        assertEquals(h.original,h.home.state.value.feed)
        h.home.touching(false); tick(h,999)
        assertEquals(h.original,h.home.state.value.feed)
        tick(h,1)
        assertEquals(feed(40),h.home.state.value.feed)
    }
    @Test fun horizontalFlingAndPlayerOverlayPreventReplacement() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter()
        h.home.viewport(true,false); h.home.shelfScrolling("picks",true)
        h.source.first = { feed(40) }; h.home.refresh(); runCurrent(); tick(h,1_000)
        assertEquals(h.original,h.home.state.value.feed)
        h.home.viewport(true,false,blocked = true); h.home.shelfScrolling("picks",false); tick(h,1_000)
        assertEquals(h.original,h.home.state.value.feed)
        h.home.viewport(true,false); tick(h,1_000)
        assertEquals(feed(40),h.home.state.value.feed)
    }
    @Test fun stagedFeedPublishesOnNextEntryAndSurvivesProcessRecreation() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter()
        h.source.first = { feed(40) }; h.home.refresh(); runCurrent(); tick(h,300)
        assertNotNull(h.cache.record?.pending)
        h.home.leave(); runCurrent()
        val restored = HomeFeedCoordinator(h.source,h.cache,backgroundScope) { h.time }
        restored.enter(); runCurrent()
        assertEquals(feed(40),restored.state.value.feed)
        assertEquals(1,h.source.calls)
    }
    @Test fun offlineAndEmptyRefreshNeverEraseCache() = runTest {
        val h = Harness(backgroundScope,stale = true); runCurrent(); h.home.enter(); runCurrent()
        assertEquals(h.original,h.home.state.value.feed)
        assertNull(h.home.state.value.error)
        assertFalse(h.home.state.value.isInitialLoading)
        h.source.first = { OnlineHomeFeed() }; h.home.refresh(); runCurrent(); tick(h,300)
        assertEquals(h.original,h.home.state.value.feed)
        assertEquals(h.original,h.cache.record?.active?.feed)
        assertNotNull(h.home.state.value.error)
    }
    @Test fun threeQualifiedSongsRespectAutomaticCooldownAndTriggerVarietySafely() = runTest {
        val h = Harness(backgroundScope)
        h.cache.record = h.cache.record!!.copy(lastAutomaticAttempt = h.time)
        runCurrent(); h.home.enter(); h.home.viewport(false,false)
        tick(h,120_000)
        h.source.events = (0..2).map { PlaybackHistoryEntry("youtube:${track(it).videoId}",h.time - it,30_000) }
        h.source.historyChanges.value++; h.source.first = { feed(40) }; runCurrent()
        assertEquals(0,h.source.calls)
        tick(h,480_000)
        assertEquals(1,h.source.calls)
        assertEquals(h.original,h.home.state.value.feed)
        h.home.viewport(true,false); tick(h,1_000)
        assertEquals(feed(40),h.home.state.value.feed)
    }
    @Test fun shortListensAndLocalSongsDoNotRefreshAndThereIsNoBackgroundPolling() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter()
        tick(h,120_000)
        h.source.events = listOf(PlaybackHistoryEntry("youtube:00000000000",h.time,29_999),
            PlaybackHistoryEntry("1",h.time,300_000), PlaybackHistoryEntry("2",h.time,300_000))
        h.source.historyChanges.value++; runCurrent()
        assertEquals(0,h.source.calls)
        h.home.leave(); tick(h,HomeFeedCoordinator.REFRESH_AGE * 2)
        assertEquals(0,h.source.calls)
        h.home.enter(); runCurrent()
        assertEquals(1,h.source.calls)
    }
    @Test fun explicitRefreshDeduplicatesAndCancelsPagingWithoutLosingSnapshot() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter()
        val page = CompletableDeferred<OnlineHomeFeed>()
        h.source.next = { withContext(NonCancellable) { page.await() } }
        h.home.loadMore(); runCurrent()
        val first = CompletableDeferred<OnlineHomeFeed>()
        h.source.first = { first.await() }
        h.home.refresh(); h.home.refresh(); runCurrent()
        assertEquals(1,h.source.calls)
        first.complete(feed(40)); runCurrent(); h.home.viewport(true,false); tick(h,1_000)
        page.complete(feed(100)); runCurrent()
        assertEquals(feed(40),h.home.state.value.feed)
        assertFalse(h.home.state.value.loadingMore)
    }
    @Test fun accountChangeClearsPrivateSnapshotsAndRejectsLateResponse() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter()
        val late = CompletableDeferred<OnlineHomeFeed>()
        h.source.first = { withContext(NonCancellable) { late.await() } }
        h.home.refresh(); runCurrent()
        h.source.first = { OnlineHomeFeed() }
        h.source.namespace.value = "other-account"; runCurrent()
        assertTrue(h.home.state.value.tracks.isEmpty())
        assertTrue(h.home.state.value.quickPicks.isEmpty())
        late.complete(feed(40)); runCurrent()
        assertTrue(h.home.state.value.tracks.isEmpty())
        assertEquals("other-account",h.home.state.value.namespace)
        assertEquals(1,h.cache.clears)
    }
    @Test fun legacyCacheMigratesAndUnavailableStorageDoesNotBlockInitialFetch() = runTest {
        val h = Harness(backgroundScope,cached = false)
        h.source.old = HomeFeedSnapshot(listOf(feed()),h.time)
        runCurrent(); h.home.enter(); tick(h,300)
        assertEquals(feed(),h.home.state.value.feed)
        assertNotNull(h.cache.record?.active)
        val brokenCache = Cache().also { it.fail = true }
        val source = Source().also { it.first = { feed(40) } }
        val home = HomeFeedCoordinator(source,brokenCache,backgroundScope) { h.time }
        home.enter(); runCurrent()
        assertEquals(feed(40),home.state.value.feed)
        assertFalse(home.state.value.isInitialLoading)
    }
    @Test fun paginationFailurePreservesContinuationAndRetryAppendsOnlyOnce() = runTest {
        val h = Harness(backgroundScope); runCurrent(); h.home.enter()
        h.home.loadMore(); runCurrent()
        assertEquals(h.original,h.home.state.value.feed)
        assertNotNull(h.home.state.value.moreError)
        h.source.next = { feed(40,token = "later") }
        h.home.loadMore(); h.home.loadMore(); runCurrent()
        assertEquals(2,h.source.pageCalls)
        assertEquals(48,h.home.state.value.tracks.size)
        assertEquals("later",h.home.state.value.continuation)
    }
}
