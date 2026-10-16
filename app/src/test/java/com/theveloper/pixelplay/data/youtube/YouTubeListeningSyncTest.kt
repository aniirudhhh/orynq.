package com.theveloper.pixelplay.data.youtube

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeListeningSyncTest {
    private class Store(var record: ListeningSyncRecord? = null) : ListeningSyncStore {
        var fail = false
        override suspend fun read() = record
        override suspend fun write(record: ListeningSyncRecord) { if (fail) error("disk"); this.record = record }
    }
    private class Source : ListeningSyncSource {
        override val namespace = MutableStateFlow("account1")
        val sent = mutableListOf<PendingListen>()
        var result = ListenReport.ACCEPTED
        var prepareDelay = 0L
        var claim = true
        override suspend fun report(event: PendingListen, beforeSend: suspend () -> Boolean): ListenReport {
            delay(prepareDelay)
            if (claim && !beforeSend()) return ListenReport.UNAVAILABLE
            sent += event
            return result
        }
    }
    @Test fun offByDefaultAndOnlyNewQualifiedOnlineSessionsReportOnce() = runTest {
        val source = Source(); val store = Store()
        val sync = YouTubeListeningSync(source, store, backgroundScope) { testScheduler.currentTime }
        runCurrent()
        assertFalse(sync.state.value.enabled)
        assertNull(sync.begin("youtube:abcdefghijk"))
        sync.setEnabled(true); runCurrent()
        assertNull(sync.begin("42"))
        assertNull(sync.begin("youtube:bad"))
        val ticket = sync.begin("youtube:abcdefghijk")!!
        sync.complete(ticket, 29_999); runCurrent()
        assertTrue(source.sent.isEmpty())
        sync.complete(ticket, 30_000); runCurrent()
        sync.complete(ticket, 90_000); runCurrent()
        assertEquals(1, source.sent.size)
        assertNotNull(sync.state.value.lastAcceptedAt)
    }
    @Test fun disableAndReenableRevokesExistingSessionAndPendingWork() = runTest {
        val source = Source().also { it.prepareDelay = 1_000 }
        val sync = YouTubeListeningSync(source, Store(), backgroundScope) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        val old = sync.begin("youtube:abcdefghijk")!!
        sync.complete(old, 40_000); runCurrent()
        sync.setEnabled(false); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertTrue(source.sent.isEmpty())
        assertEquals(0, sync.state.value.pending)
        sync.setEnabled(true); runCurrent()
        sync.complete(old, 40_000); runCurrent()
        assertEquals(0, sync.state.value.pending)
    }
    @Test fun accountChangeCannotReportOldAccountSessions() = runTest {
        val source = Source().also { it.prepareDelay = 1_000 }
        val store = Store(); val sync = YouTubeListeningSync(source, store, backgroundScope) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        val old = sync.begin("youtube:abcdefghijk")!!
        sync.complete(old, 40_000); runCurrent()
        source.namespace.value = "account2"; runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertTrue(source.sent.isEmpty())
        assertFalse(sync.state.value.enabled)
        assertEquals("account2", store.record!!.namespace)
        sync.setEnabled(true); runCurrent(); sync.complete(old, 40_000); runCurrent()
        assertTrue(source.sent.isEmpty())
    }
    @Test fun safePreparationRetriesAreBoundedAndNoSendIsClaimed() = runTest {
        val source = Source().also { it.result = ListenReport.RETRY; it.claim = false }
        val sync = YouTubeListeningSync(source, Store(), backgroundScope) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        sync.complete(sync.begin("youtube:abcdefghijk"), 40_000); runCurrent()
        assertEquals(1, source.sent.size)
        advanceTimeBy(30_000); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(3, source.sent.size)
        assertEquals(0, sync.state.value.pending)
        assertEquals("failed", sync.state.value.notice)
    }
    @Test fun uncertainReportsAreNotRetried() = runTest {
        val source = Source().also { it.result = ListenReport.UNCONFIRMED }
        val sync = YouTubeListeningSync(source, Store(), backgroundScope) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        sync.complete(sync.begin("youtube:abcdefghijk"), 40_000); runCurrent()
        advanceTimeBy(180_000); runCurrent()
        assertEquals(1, source.sent.size)
        assertEquals("unconfirmed", sync.state.value.notice)
        assertEquals(0, sync.state.value.pending)
    }
    @Test fun restartDropsClaimedSendsButRestoresSafePendingQueue() = runTest {
        val ticket = ListeningSyncTicket("account1", "revision", "abcdefghijk")
        val safe = ticket.copy(id = "another")
        val store = Store(ListeningSyncRecord("account1", true, "revision", listOf(
            PendingListen(ticket, 40_000, 0, claimed = true), PendingListen(safe, 40_000, 0))))
        val source = Source()
        val sync = YouTubeListeningSync(source, store, backgroundScope) { testScheduler.currentTime }
        runCurrent()
        assertEquals(listOf("another"), source.sent.map { it.ticket.id })
        assertTrue(ticket.id in store.record!!.completed)
        assertTrue(sync.state.value.enabled)
    }
    @Test fun failedPersistenceNeverAllowsSending() = runTest {
        val source = Source(); val store = Store()
        val sync = YouTubeListeningSync(source, store, backgroundScope) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        val ticket = sync.begin("youtube:abcdefghijk")!!
        store.fail = true
        sync.complete(ticket, 40_000); runCurrent()
        assertTrue(source.sent.isEmpty())
        assertFalse(sync.state.value.ready)
        assertNull(sync.begin("youtube:abcdefghijk"))
    }
    @Test fun rapidTogglesUseLatestConsentAndExpiredReportsAreDropped() = runTest {
        val ticket = ListeningSyncTicket("account1", "r", "abcdefghijk")
        val source = Source()
        val store = Store(ListeningSyncRecord("account1", true, "r", listOf(PendingListen(ticket, 40_000, 0))))
        val sync = YouTubeListeningSync(source, store, backgroundScope) { 86_400_001L }
        runCurrent()
        assertTrue(source.sent.isEmpty())
        assertEquals(0, sync.state.value.pending)
        sync.setEnabled(true); sync.setEnabled(false); sync.setEnabled(true); sync.setEnabled(false)
        runCurrent()
        assertFalse(sync.state.value.enabled)
        assertNull(sync.begin("youtube:abcdefghijk"))
    }
    @Test fun reconnectStopsFutureReportsUntilExplicitConsentReset() = runTest {
        val source = Source().also { it.result = ListenReport.RECONNECT }
        val sync = YouTubeListeningSync(source, Store(), backgroundScope) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        sync.complete(sync.begin("youtube:abcdefghijk"), 40_000); runCurrent()
        assertEquals("reconnect", sync.state.value.notice)
        assertNull(sync.begin("youtube:abcdefghijk"))
        advanceTimeBy(180_000); runCurrent()
        assertEquals(1, source.sent.size)
    }

    @Test fun offlineQueueSurvivesWithoutConsumingRetriesAndFlushesOnReconnect() = runTest {
        val internet = MutableStateFlow(false)
        val source = Source(); val store = Store()
        val sync = YouTubeListeningSync(source, store, backgroundScope, internet) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        sync.complete(sync.begin("youtube:abcdefghijk"), 40_000); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertTrue(source.sent.isEmpty())
        assertEquals(0, store.record!!.queue.single().attempts)
        assertTrue(sync.state.value.waitingForInternet)
        internet.value = true; runCurrent()
        assertEquals(1, source.sent.size)
        assertEquals(0, sync.state.value.pending)
        assertFalse(sync.state.value.waitingForInternet)
    }
    @Test fun disconnectDuringPreparationDefersBeforeSendWithoutSpendingRetry() = runTest {
        val internet = MutableStateFlow(true)
        val source = Source().also { it.prepareDelay = 1_000 }
        val store = Store()
        val sync = YouTubeListeningSync(source, store, backgroundScope, internet) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        sync.complete(sync.begin("youtube:abcdefghijk"), 40_000); runCurrent()
        internet.value = false; runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertTrue(source.sent.isEmpty())
        assertEquals(0, store.record!!.queue.single().attempts)
        assertFalse(store.record!!.queue.single().claimed)
        internet.value = true; runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, source.sent.size)
    }
    @Test fun uncertainSendStillNeverReplaysAfterInternetReturns() = runTest {
        val internet = MutableStateFlow(true)
        val source = Source().also { it.result = ListenReport.UNCONFIRMED }
        val sync = YouTubeListeningSync(source, Store(), backgroundScope, internet) { testScheduler.currentTime }
        runCurrent(); sync.setEnabled(true); runCurrent()
        sync.complete(sync.begin("youtube:abcdefghijk"), 40_000); runCurrent()
        internet.value = false; runCurrent(); advanceTimeBy(60_000)
        internet.value = true; runCurrent()
        assertEquals(1, source.sent.size)
        assertEquals(0, sync.state.value.pending)
    }

}
