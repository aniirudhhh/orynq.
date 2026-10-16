package com.theveloper.pixelplay.data.service.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlinePlaybackRecoveryTest {
    @Test fun offlineWaitDoesNotConsumeAttemptsAndResumesAtLatestPosition() = runTest {
        val internet = MutableStateFlow(false)
        var current = RecoveryPlayback("youtube:abcdefghijk", 12_000, true)
        val retries = mutableListOf<RecoveryPlayback>()
        val recovery = OnlinePlaybackRecovery(backgroundScope, internet, { current }, { retries += it }, now = { testScheduler.currentTime })
        runCurrent(); assertTrue(recovery.failed(true))
        advanceTimeBy(600_000); runCurrent(); assertTrue(retries.isEmpty())
        current = current.copy(positionMs = 77_000) // User seek while offline wins over the old position.
        internet.value = true; runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(listOf(77_000L), retries.map { it.positionMs })
        assertEquals("youtube:abcdefghijk", retries.single().mediaId)
    }
    @Test fun pauseStopAndTrackChangeCancelPendingResume() = runTest {
        val internet = MutableStateFlow(false)
        var current = RecoveryPlayback("youtube:abcdefghijk", 1_000, true)
        var retries = 0
        val recovery = OnlinePlaybackRecovery(backgroundScope, internet, { current }, { retries++ }, now = { testScheduler.currentTime })
        runCurrent(); recovery.failed(true)
        current = current.copy(wantsPlay = false); recovery.intentChanged(false)
        internet.value = true; runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(0, retries)
        internet.value = false; runCurrent(); current = current.copy(wantsPlay = true)
        recovery.failed(true); recovery.stopped()
        internet.value = true; runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(0, retries)
        internet.value = false; runCurrent(); recovery.failed(true)
        current = current.copy(mediaId = "youtube:zzzzzzzzzzz"); recovery.trackChanged()
        internet.value = true; runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(0, retries)
    }
    @Test fun threeAttemptsAreBoundedUntilSustainedSuccessfulPlayback() = runTest {
        val internet = MutableStateFlow(true)
        var current = RecoveryPlayback("youtube:abcdefghijk", 1_000, true)
        var retries = 0
        val recovery = OnlinePlaybackRecovery(backgroundScope, internet, { current }, { retries++ }, now = { testScheduler.currentTime })
        runCurrent()
        for (wait in listOf(500L, 2_000L, 5_000L)) {
            assertTrue(recovery.failed(true)); runCurrent(); advanceTimeBy(wait); runCurrent()
        }
        assertEquals(3, retries)
        assertFalse(recovery.failed(true))
        current = current.copy(playing = true); recovery.playingChanged(true)
        advanceTimeBy(15_000); runCurrent()
        current = current.copy(playing = false)
        assertTrue(recovery.failed(true)); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(4, retries)
    }
    @Test fun networkFlapsCancelScheduledWorkAndLocalCastErrorsNeverRecover() = runTest {
        val internet = MutableStateFlow(false)
        var current = RecoveryPlayback("youtube:abcdefghijk", 1_000, true)
        var retries = 0
        val recovery = OnlinePlaybackRecovery(backgroundScope, internet, { current }, { retries++ }, now = { testScheduler.currentTime })
        runCurrent(); recovery.failed(true)
        internet.value = true; runCurrent(); advanceTimeBy(200)
        internet.value = false; runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(0, retries)
        internet.value = true; runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, retries)
        recovery.stopped(); current = current.copy(mediaId = "42")
        assertFalse(recovery.failed(true))
        current = current.copy(mediaId = "youtube:abcdefghijk", localPlayer = false)
        assertFalse(recovery.failed(true))
        current = current.copy(localPlayer = true)
        assertFalse(recovery.failed(false))
    }
    @Test fun expiryAndServiceShutdownNeverAutoplayLater() = runTest {
        val internet = MutableStateFlow(false)
        val current = RecoveryPlayback("youtube:abcdefghijk", 1_000, true)
        var retries = 0; var expired = 0
        val recovery = OnlinePlaybackRecovery(backgroundScope, internet, { current }, { retries++ },
            expired = { expired++ }, now = { testScheduler.currentTime })
        runCurrent(); recovery.failed(true)
        advanceTimeBy(OnlinePlaybackRecovery.MAX_WAIT); runCurrent()
        assertEquals(1, expired)
        internet.value = true; runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(0, retries)
        assertFalse(recovery.failed(true))
        recovery.trackChanged(); internet.value = false; runCurrent(); recovery.failed(true)
        recovery.close(); internet.value = true; runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(0, retries)
    }
}
