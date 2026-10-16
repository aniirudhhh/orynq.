package com.theveloper.pixelplay.data.youtube

import android.content.Context
import android.content.SharedPreferences
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository.PlaybackHistoryEntry
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OnlineDailyMixTest {
    private val now = 1_800_000_000_000L
    private fun track(id: Char, artist: String = "Artist $id") = OnlineTrack(id.toString().repeat(11), "Song $id", artist, null)
    private fun play(track: OnlineTrack, ageDays: Int = 0, listened: Long = 60_000) =
        PlaybackHistoryEntry("youtube:${track.videoId}", now - ageDays * 86_400_000L, listened)

    @Test fun seedsExcludeSkipsLocalSongsUnknownMetadataAndOldHistory() {
        val a = track('a'); val b = track('b'); val c = track('c')
        val history = listOf(play(a), play(b, listened = 29_999), play(c, ageDays = 31),
            PlaybackHistoryEntry("local-song", now, 300_000), play(track('z')))
        assertEquals(listOf(a), onlineMixSeeds(history, listOf(a,b,c), now))
    }
    @Test fun frequentRecentListeningOutranksOlderSongsAndDiversifiesSeeds() {
        val a = track('a'); val b = track('b'); val c = track('c', a.artist)
        assertEquals(listOf(a,b), onlineMixSeeds(listOf(play(b, 20), play(a), play(a), play(c)), listOf(a,b,c), now))
    }
    @Test fun mixContainsDiscoveriesDeduplicatesAndPreservesVideoIds() {
        val a = track('a'); val b = track('b'); val c = track('c')
        val mix = assembleOnlineMix(listOf(a), listOf(listOf(a,b,c), listOf(b)), LocalDate.of(2026,10,14))
        assertEquals(setOf(a,b,c), mix.toSet())
        assertEquals(mix.size, mix.map { it.videoId }.distinct().size)
    }
    @Test fun mixIsStablePerDayAndCapsDominantArtistAndTotalSize() {
        val seeds = listOf(track('a'))
        val pool = (0 until 80).map { i -> OnlineTrack("%011d".format(i), "Song $i", if (i < 30) "Same artist" else "Artist $i", null) }
        val day = LocalDate.of(2026,10,14)
        val mix = assembleOnlineMix(seeds, listOf(pool), day)
        assertEquals(30, mix.size)
        assertTrue(mix.count { it.artist == "Same artist" } <= 6)
        assertEquals(mix, assembleOnlineMix(seeds, listOf(pool), day))
        assertNotEquals(mix, assembleOnlineMix(seeds, listOf(pool), day.plusDays(1)))
    }
    @Test fun localMidnightAndYearRolloverProduceNewDay() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026,12,31)
        val last = date.atTime(23,59).atZone(zone).toInstant().toEpochMilli()
        assertNotEquals(mixDay(last, zone), mixDay(last + 120_000, zone))
    }

    private fun manager(values: MutableMap<String,String>, youtube: YouTubeRepository): OnlineDailyMixManager {
        val context = mockk<Context>(); val prefs = mockk<SharedPreferences>(); val editor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences("online_daily_mix", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
        every { editor.apply() } just Runs
        return OnlineDailyMixManager(context, youtube)
    }
    @Test fun sameDayCacheSurvivesRestartWithoutNetworkRequests() = runTest {
        val a = track('a'); val youtube = mockk<YouTubeRepository>()
        val values = mutableMapOf("day" to mixDay(System.currentTimeMillis()).toString(), "tracks" to Json.encodeToString(listOf(a)))
        assertEquals(listOf(a), manager(values, youtube).generate(emptyList()))
        coVerify(exactly = 0) { youtube.songRadio(any()) }
        verify(exactly = 0) { youtube.knownOnlineTracks() }
    }
    @Test fun failedRefreshRetainsPreviousMixAndDoesNotMarkItFresh() = runTest {
        val a = track('a'); val youtube = mockk<YouTubeRepository>()
        val values = mutableMapOf("day" to "2020-01-01", "tracks" to Json.encodeToString(listOf(a)))
        every { youtube.knownOnlineTracks() } returns listOf(a)
        coEvery { youtube.songRadio(a.videoId) } throws IllegalStateException("offline")
        val history = listOf(PlaybackHistoryEntry("youtube:${a.videoId}", System.currentTimeMillis(), 60_000))
        assertEquals(listOf(a), manager(values,youtube).generate(history))
        assertEquals("2020-01-01", values["day"])
    }
    @Test fun firstQualifiedListenBuildsPersistsAndCanBeForceRefreshed() = runTest {
        val a=track('a'); val b=track('b'); val youtube=mockk<YouTubeRepository>()
        every { youtube.knownOnlineTracks() } returns listOf(a)
        coEvery { youtube.songRadio(a.videoId) } returns listOf(b)
        val values=mutableMapOf<String,String>(); val manager=manager(values,youtube)
        val history=listOf(PlaybackHistoryEntry("youtube:${a.videoId}",System.currentTimeMillis(),60_000))
        assertEquals(setOf(a,b),manager.generate(history)!!.toSet())
        assertEquals(setOf(a,b),manager.cached().toSet())
        manager.generate(history,force=true)
        coVerify(exactly=2) { youtube.songRadio(a.videoId) }
    }
    @Test fun cancellationNeverCommitsAPartialMix() = runTest {
        val a=track('a');val youtube=mockk<YouTubeRepository>(); val values=mutableMapOf<String,String>()
        every { youtube.knownOnlineTracks() } returns listOf(a)
        coEvery { youtube.songRadio(a.videoId) } throws CancellationException()
        try { manager(values,youtube).generate(listOf(PlaybackHistoryEntry("youtube:${a.videoId}",System.currentTimeMillis(),60_000))); fail("Expected cancellation") }
        catch (_:CancellationException) { assertTrue(values.isEmpty()) }
    }
}
