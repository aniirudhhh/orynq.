package com.theveloper.pixelplay.data.youtube

import android.content.Context
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository.PlaybackHistoryEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

internal fun mixDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

internal fun onlineMixSeeds(history: List<PlaybackHistoryEntry>, known: List<OnlineTrack>, now: Long): List<OnlineTrack> {
    val metadata = known.associateBy { "youtube:${it.videoId}" }
    val cutoff = now - 30L * 24 * 60 * 60 * 1000
    return history.filter { it.songId in metadata && it.timestamp in cutoff..now && it.listenedMs >= 30_000 }
        .groupBy { it.songId }
        .entries.sortedByDescending { (_, plays) ->
            plays.sumOf { play ->
                val ageDays = (now - play.timestamp) / (24.0 * 60 * 60 * 1000)
                (play.listenedMs.coerceAtMost(600_000) / 30_000.0) / (1.0 + ageDays)
            }
        }.mapNotNull { metadata[it.key] }.distinctBy { it.artist.lowercase(java.util.Locale.ROOT) }.take(3)
}

internal fun assembleOnlineMix(seeds: List<OnlineTrack>, radios: List<List<OnlineTrack>>, day: LocalDate): List<OnlineTrack> {
    val result = linkedMapOf<String, OnlineTrack>()
    val artistCounts = mutableMapOf<String, Int>()
    fun add(track: OnlineTrack) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(track.videoId) || track.title.isBlank() || track.videoId in result) return
        val artist = track.artist.lowercase(java.util.Locale.ROOT)
        if ((artistCounts[artist] ?: 0) >= 6) return
        result[track.videoId] = track
        artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
    }
    seeds.forEach(::add)
    // Round-robin seed radios so one artist's recommendations do not dominate the mix.
    for (index in 0 until (radios.maxOfOrNull { it.size } ?: 0)) {
        radios.forEach { it.getOrNull(index)?.let(::add) }
    }
    return result.values.shuffled(kotlin.random.Random(day.toEpochDay())).take(30)
}

@Singleton
class OnlineDailyMixManager @Inject constructor(
    @ApplicationContext context: Context,
    private val youtube: YouTubeRepository
) {
    private val preferences = context.getSharedPreferences("online_daily_mix", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun cached(): List<OnlineTrack> = runCatching {
        json.decodeFromString<List<OnlineTrack>>(preferences.getString("tracks", "[]").orEmpty())
    }.getOrDefault(emptyList())
    fun save(tracks: List<OnlineTrack>, now: Long = System.currentTimeMillis()) {
        preferences.edit().putString("tracks", json.encodeToString(tracks))
            .putString("day", mixDay(now).toString()).apply()
    }
    suspend fun generate(history: List<PlaybackHistoryEntry>, force: Boolean = false): List<OnlineTrack>? {
        val now = System.currentTimeMillis()
        val previous = cached()
        if (!force && previous.isNotEmpty() && preferences.getString("day", "") == mixDay(now).toString()) return previous
        val seeds = onlineMixSeeds(history, youtube.knownOnlineTracks() + previous, now)
        if (seeds.isEmpty()) return previous.takeIf { it.isNotEmpty() }
        val radios = coroutineScope {
            seeds.map { seed -> async {
                try { withTimeoutOrNull(20_000L) { youtube.songRadio(seed.videoId) }.orEmpty() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            } }.awaitAll()
        }
        // Failed refreshes retain yesterday's usable mix; fresh installs can use their listened songs.
        if (radios.all { it.isEmpty() } && previous.isNotEmpty()) return previous
        return assembleOnlineMix(seeds, radios, mixDay(now)).also { if (it.isNotEmpty()) save(it, now) }
    }
}
