package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository.PlaybackHistoryEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class HomeFeedUiState(
    val feed: OnlineHomeFeed = OnlineHomeFeed(),
    val quickPicks: List<OnlineTrack> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isPullRefreshing: Boolean = false,
    val isBackgroundRefreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val moreError: String? = null,
    val visible: Boolean = false,
    val namespace: String = "",
    val contentVersion: Long = 0,
) {
    val tracks get() = feed.tracks
    val shelves get() = feed.shelves
    val continuation get() = feed.continuation
}

internal interface HomeFeedSource {
    val namespace: StateFlow<String>
    val historyChanges: Flow<Long>
    suspend fun history(): List<PlaybackHistoryEntry>
    suspend fun legacy(): HomeFeedSnapshot?
    suspend fun firstPage(): OnlineHomeFeed
    suspend fun nextPage(token: String): OnlineHomeFeed
    suspend fun enrich(feed: OnlineHomeFeed): OnlineHomeFeed
}

private class RepositoryHomeSource(private val repository: YouTubeRepository,
    private val stats: PlaybackStatsRepository) : HomeFeedSource {
    override val namespace = repository.homeCacheNamespace
    override val historyChanges = stats.refreshFlow
    override suspend fun history() = stats.loadPlaybackHistory()
    override suspend fun legacy() = withContext(Dispatchers.IO) { repository.legacyHomeSnapshot() }
    override suspend fun firstPage() = repository.homeFeed()
    override suspend fun nextPage(token: String) = repository.homePage(token)
    override suspend fun enrich(feed: OnlineHomeFeed) = repository.enrichHomeFeed(feed)
}

/** All state transitions run on Main; cache implementations own their IO dispatcher. */
@Singleton
class HomeFeedCoordinator internal constructor(
    private val source: HomeFeedSource,
    private val cache: HomeFeedCache,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    @Inject constructor(repository: YouTubeRepository, cache: AtomicHomeFeedCache, stats: PlaybackStatsRepository) :
        this(RepositoryHomeSource(repository, stats), cache, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), System::currentTimeMillis)

    private val mutableState = MutableStateFlow(HomeFeedUiState())
    val state = mutableState.asStateFlow()
    private var namespace = source.namespace.value
    private var epoch = 0L
    private var record = HomeCacheRecord(namespace)
    private var history = emptyList<PlaybackHistoryEntry>()
    private var selected = emptyList<OnlineTrack>()
    private var selectionHistoryThrough = 0L
    private var visible = false
    private var ready = false
    private var leftAt: Long? = null
    private var lastAutomaticAttempt: Long? = null
    private var atTop = false
    private var scrolling = false
    private var touching = false
    private var blocked = false
    private val shelfScrolling = mutableSetOf<String>()
    private var rotationPending = false
    private var hydration: Job? = null
    private var request: Job? = null
    private var artwork: Job? = null
    private var idle: Job? = null
    private var timer: Job? = null
    private var persistence: Job? = null
    private val storageLock = Mutex()

    init {
        activate(namespace, initial = true)
        scope.launch { source.namespace.collect { if (it != namespace) activate(it) } }
        scope.launch { source.historyChanges.drop(1).collect {
            val generation = epoch
            val latest = try { source.history() }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { history }
            if (generation == epoch) {
                history = latest
                if (qualifiedSince(selectionHistoryThrough).size >= 3) rotationPending = true
                armIdle(); maybeRefresh(); scheduleRefresh()
            }
        } }
    }

    private fun activate(key: String, initial: Boolean = false) {
        ++epoch
        namespace = key
        hydration?.cancel(); request?.cancel(); artwork?.cancel(); idle?.cancel(); timer?.cancel(); persistence?.cancel()
        record = HomeCacheRecord(key); selected = emptyList(); ready = false; history = emptyList()
        selectionHistoryThrough = 0; leftAt = null; rotationPending = false; lastAutomaticAttempt = null
        shelfScrolling.clear()
        mutableState.value = HomeFeedUiState(visible = visible, namespace = key,
            contentVersion = mutableState.value.contentVersion + 1)
        val generation = epoch
        hydration = scope.launch {
            val restored = try { storageLock.withLock {
                if (!initial) cache.clear()
                cache.read(key) ?: source.legacy()?.takeIf { it.feed.isUsable() }?.let { HomeCacheRecord(key, active = it) }
            } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val restoredHistory = try { source.history() }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
            if (generation != epoch) return@launch
            record = restored ?: HomeCacheRecord(key)
            lastAutomaticAttempt = record.lastAutomaticAttempt?.takeIf { it <= now() }
            history = restoredHistory
            ready = true
            if (visible && record.pending != null) record = record.copy(active = record.pending, pending = null)
            record.active?.let { updateCandidates(it) }
            chooseSelection()
            publishActive()
            persist()
            if (visible) maybeRefresh()
            scheduleRefresh()
        }
    }

    private fun ensureAccount() { if (source.namespace.value != namespace) activate(source.namespace.value) }

    fun enter() {
        ensureAccount()
        if (visible) return
        visible = true
        mutableState.value = mutableState.value.copy(visible = true, error = null)
        if (!ready) return
        val pending = record.pending
        if (pending != null) {
            record = record.copy(active = pending, pending = null)
            chooseSelection(); publishActive(); persist()
        } else if (leftAt?.let { now() - it >= SESSION_GAP || now() < it } == true || qualifiedSince(selectionHistoryThrough).size >= 3) {
            chooseSelection(); publishActive(); persist()
        }
        rotationPending = false
        maybeRefresh(); armIdle(); scheduleRefresh()
    }

    fun leave() {
        if (!visible) return
        visible = false; leftAt = now(); touching = false
        idle?.cancel(); timer?.cancel(); artwork?.cancel(); shelfScrolling.clear()
        mutableState.value = mutableState.value.copy(visible = false)
        persist(immediate = true)
    }

    fun viewport(atTop: Boolean, scrolling: Boolean, blocked: Boolean = false) {
        if (this.atTop == atTop && this.scrolling == scrolling && this.blocked == blocked) return
        this.atTop = atTop; this.scrolling = scrolling; this.blocked = blocked
        armIdle()
    }
    fun touching(value: Boolean) { if (touching != value) { touching = value; armIdle() } }
    fun shelfScrolling(key: String, value: Boolean) {
        if (if (value) shelfScrolling.add(key) else shelfScrolling.remove(key)) armIdle()
    }
    private fun isSafeToApply() = visible && atTop && !scrolling && !touching && !blocked && shelfScrolling.isEmpty()
    private fun armIdle() {
        idle?.cancel()
        if (!ready || !isSafeToApply() || (record.pending == null && !rotationPending)) return
        val generation = epoch
        idle = scope.launch {
            delay(1_000)
            if (generation != epoch || !isSafeToApply()) return@launch
            record.pending?.let { record = record.copy(active = it, pending = null) }
            rotationPending = false
            chooseSelection(); publishActive(); persist()
        }
    }

    private fun qualifiedSince(timestamp: Long) = history.filter {
        it.songId.startsWith("youtube:") && it.listenedMs >= 30_000 && it.timestamp > timestamp && it.timestamp <= now()
    }.map { it.songId }.toSet()

    private fun refreshDue(): Long {
        val snapshot = record.pending ?: record.active ?: return lastAutomaticAttempt?.plus(AUTO_COOLDOWN) ?: now()
        val staleAt = if (now() < snapshot.fetchedAt) now() else snapshot.fetchedAt + REFRESH_AGE
        val desired = if (qualifiedSince(snapshot.historyThrough).size >= 3) now() else staleAt
        return maxOf(desired, lastAutomaticAttempt?.plus(AUTO_COOLDOWN) ?: Long.MIN_VALUE)
    }
    private fun maybeRefresh() {
        if (ready && visible && request?.isActive != true && now() >= refreshDue()) refresh(force = false)
    }
    private fun scheduleRefresh() {
        timer?.cancel()
        if (!visible || !ready) return
        timer = scope.launch { delay((refreshDue() - now()).coerceAtLeast(1_000)); maybeRefresh() }
    }

    fun refresh(force: Boolean = true) {
        ensureAccount()
        if (!ready) {
            if (force) scope.launch { hydration?.join(); if (visible) refresh(true) }
            return
        }
        if (!force && (!visible || request?.isActive == true)) return
        if (force && mutableState.value.isPullRefreshing) return
        request?.cancel(); artwork?.cancel(); timer?.cancel()
        val generation = ++requestGeneration
        val accountEpoch = epoch
        val watermark = history.maxOfOrNull { it.timestamp } ?: 0L
        if (!force) {
            lastAutomaticAttempt = now()
            record = record.copy(lastAutomaticAttempt = lastAutomaticAttempt)
            persist()
        }
        mutableState.value = mutableState.value.copy(isInitialLoading = !mutableState.value.feed.isUsable(),
            isPullRefreshing = force, isBackgroundRefreshing = !force, loadingMore = false, error = null, moreError = null)
        request = scope.launch {
            try {
                val feed = source.firstPage()
                if (!isCurrent(accountEpoch, generation)) return@launch
                check(feed.isUsable()) { "Empty Home response" }
                val snapshot = HomeFeedSnapshot(listOf(feed), now(), watermark)
                updateCandidates(snapshot)
                if (record.active?.feed?.isUsable() == true) {
                    record = record.copy(pending = snapshot)
                } else {
                    record = record.copy(active = snapshot, pending = null)
                    chooseSelection(); publishActive()
                }
                mutableState.value = mutableState.value.copy(isInitialLoading = false, isPullRefreshing = false, isBackgroundRefreshing = false)
                persist(); armIdle()
                if (visible) enrich(snapshot, accountEpoch, generation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isCurrent(accountEpoch, generation)) mutableState.value = mutableState.value.copy(
                    isInitialLoading = false, isPullRefreshing = false, isBackgroundRefreshing = false,
                    error = if (!mutableState.value.feed.isUsable() || force) "Couldn't refresh YouTube Music. Please try again." else null)
            } finally { if (isCurrent(accountEpoch, generation)) scheduleRefresh() }
        }
    }

    private var requestGeneration = 0L
    private fun isCurrent(accountEpoch: Long, generation: Long) = epoch == accountEpoch && generation == requestGeneration && source.namespace.value == namespace

    fun loadMore() {
        ensureAccount()
        if (!visible || !ready || request?.isActive == true) return
        val snapshot = record.active ?: return
        val token = snapshot.feed.continuation ?: return
        val generation = ++requestGeneration
        val accountEpoch = epoch
        mutableState.value = mutableState.value.copy(loadingMore = true, moreError = null)
        request = scope.launch {
            try {
                val page = source.nextPage(token)
                if (!isCurrent(accountEpoch, generation) || record.active?.id != snapshot.id || record.active?.feed?.continuation != token) return@launch
                val current = record.active ?: return@launch
                val next = current.copy(pages = current.pages + page)
                record = record.copy(active = next)
                updateCandidates(next)
                publishActive(reselect = false)
                persist()
                if (visible) enrich(next, accountEpoch, generation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isCurrent(accountEpoch, generation)) mutableState.value = mutableState.value.copy(
                loadingMore = false, moreError = "Couldn't load more recommendations. Please try again.") }
            finally { if (isCurrent(accountEpoch, generation)) { mutableState.value = mutableState.value.copy(loadingMore = false); scheduleRefresh() } }
        }
    }

    private fun enrich(snapshot: HomeFeedSnapshot, accountEpoch: Long, generation: Long) {
        artwork?.cancel()
        artwork = scope.launch {
            try {
                val feed = source.enrich(snapshot.feed)
                if (!isCurrent(accountEpoch, generation)) return@launch
                val covers = feed.tracks.associate { it.videoId to it.artwork }
                fun apply(value: HomeFeedSnapshot): HomeFeedSnapshot = value.copy(pages = value.pages.map { page ->
                    page.copy(tracks = page.tracks.map { track -> covers[track.videoId]?.let { track.copy(artwork = it) } ?: track },
                        shelves = page.shelves.map { shelf -> shelf.copy(items = shelf.items.map { item ->
                            item.track?.let { track -> covers[track.videoId]?.let { item.copy(artwork = it, track = track.copy(artwork = it)) } } ?: item
                        }) })
                })
                record = record.copy(active = record.active?.let { if (it.id == snapshot.id) apply(it) else it },
                    pending = record.pending?.let { if (it.id == snapshot.id) apply(it) else it },
                    candidates = record.candidates.map { batch -> batch.copy(tracks = batch.tracks.map { track ->
                        covers[track.videoId]?.let { track.copy(artwork = it) } ?: track }) })
                selected = selected.map { track -> covers[track.videoId]?.let { track.copy(artwork = it) } ?: track }
                publishActive(reselect = false, artworkOnly = true); persist()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional artwork must never invalidate a usable feed. */ }
        }
    }

    private fun updateCandidates(snapshot: HomeFeedSnapshot) {
        val batch = QuickPickBatch(quickPickCandidates(snapshot.feed), snapshot.fetchedAt)
        if (batch.tracks.isEmpty()) return
        record = record.copy(candidates = boundedQuickPickBatches((listOf(batch) + record.candidates.filter { it.fetchedAt != batch.fetchedAt })
            .filter { now() - it.fetchedAt in 0..48 * 60 * 60_000L }))
    }
    private fun chooseSelection() {
        record = record.copy(session = record.session + 1)
        val played = history.filter { it.listenedMs >= 30_000 && now() - it.timestamp in 0..24 * 60 * 60_000L }.map { it.songId }.toSet()
        selected = selectQuickPicks(record.candidates, record.exposures, played, record.session, now())
            .ifEmpty { record.active?.feed?.let(::quickPickCandidates).orEmpty().take(16) }
        selectionHistoryThrough = history.maxOfOrNull { it.timestamp } ?: 0L
    }
    private fun publishActive(reselect: Boolean = false, artworkOnly: Boolean = false) {
        if (reselect) chooseSelection()
        mutableState.value = mutableState.value.copy(feed = record.active?.feed ?: OnlineHomeFeed(), quickPicks = selected,
            isInitialLoading = !ready, visible = visible, namespace = namespace,
            contentVersion = mutableState.value.contentVersion + if (artworkOnly) 0 else 1)
    }

    fun seen(videoId: String) {
        ensureAccount()
        if (!visible || blocked || selected.none { it.videoId == videoId } || record.exposures[videoId]?.session == record.session) return
        record = record.copy(exposures = (record.exposures + (videoId to HomeExposure(now(), record.session)))
            .entries.sortedByDescending { it.value.seenAt }.take(256).associate { it.toPair() })
        persist()
    }
    private fun persist(immediate: Boolean = false) {
        if (!ready) return
        persistence?.cancel()
        val snapshot = record
        val generation = epoch
        persistence = scope.launch {
            if (!immediate) delay(250)
            try { storageLock.withLock { if (generation == epoch) cache.write(snapshot) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the in-memory snapshot if storage is temporarily unavailable. */ }
        }
    }
    companion object {
        internal const val REFRESH_AGE = 30 * 60_000L
        internal const val AUTO_COOLDOWN = 10 * 60_000L
        internal const val SESSION_GAP = 10 * 60_000L
    }
}
