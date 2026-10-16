package com.theveloper.pixelplay.data.youtube

import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeFeedCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun track(n: Int) = OnlineTrack(n.toString().padStart(11,'0'),"Song $n","Artist",null)
    @Test fun atomicCacheRestoresOnlyCompleteThreePagesAndCorrectContinuation() = runTest {
        val pages = (1..5).map { OnlineHomeFeed(tracks = listOf(track(it)),continuation = "page${it+1}") }
        val cache = AtomicHomeFeedCache(temporary.root)
        val snapshot = HomeFeedSnapshot(pages,100)
        cache.write(HomeCacheRecord("account",active = snapshot,pending = snapshot.copy(fetchedAt = 200)))
        val loaded = cache.read("account")!!
        assertEquals(3,loaded.active!!.pages.size)
        assertEquals("page4",loaded.active!!.feed.continuation)
        assertEquals(3,loaded.pending!!.pages.size)
        assertNull(cache.read("other"))
    }
    @Test fun interruptedTempFileCannotReplaceLastSuccessfulSnapshotAndCorruptionIsIgnored() = runTest {
        val cache = AtomicHomeFeedCache(temporary.root)
        val record = HomeCacheRecord("account",active = HomeFeedSnapshot(listOf(OnlineHomeFeed(tracks = listOf(track(1)))),100))
        cache.write(record)
        File(temporary.root,"snapshot.tmp").writeText("{partial")
        assertEquals(record,cache.read("account"))
        File(temporary.root,"snapshot.json").writeText("broken")
        assertNull(cache.read("account"))
        cache.clear()
        assertFalse(File(temporary.root,"snapshot.tmp").exists())
    }
    @Test fun candidateAndExposureRecordsStayBounded() = runTest {
        val cache = AtomicHomeFeedCache(temporary.root)
        cache.write(HomeCacheRecord("a",candidates = listOf(QuickPickBatch((0..70).map(::track),100),
            QuickPickBatch((71..150).map(::track),90)), exposures = (0..300).associate { "$it" to HomeExposure(it.toLong(),1) }))
        val stored = cache.read("a")!!
        assertEquals(64,stored.candidates.sumOf { it.tracks.size })
        assertEquals(256,stored.exposures.size)
        assertTrue("300" in stored.exposures)
    }
    @Test fun varietyDemotesSeenAndRecentlyPlayedAndExpiresOldBatches() {
        val now = 200_000_000L
        val tracks = (0..23).map(::track)
        val seen = tracks.take(4).associate { it.videoId to HomeExposure(now-100,4) }
        val selected = selectQuickPicks(listOf(QuickPickBatch(tracks,now)),seen,setOf("youtube:${track(4).videoId}"),5,now)
        assertEquals(track(5),selected.first())
        assertEquals(16,selected.map { it.videoId }.distinct().size)
        assertTrue(selectQuickPicks(listOf(QuickPickBatch(tracks,now-49*60*60_000L)),emptyMap(),emptySet(),1,now).isEmpty())
    }
    @Test fun smallPoolsKeepSongsRatherThanCreatingEmptySlotsAndRankingIsDeterministic() {
        val now = 10_000L
        val batch = listOf(QuickPickBatch(listOf(track(1),track(1),track(2)),now))
        val seen = mapOf(track(1).videoId to HomeExposure(now,2))
        val first = selectQuickPicks(batch,seen,emptySet(),3,now)
        assertEquals(listOf(track(2),track(1)),first)
        assertEquals(first,selectQuickPicks(batch,seen,emptySet(),3,now))
    }
}
