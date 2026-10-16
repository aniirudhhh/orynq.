package com.theveloper.pixelplay.data.youtube

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class HomeFeedSnapshot(val pages: List<OnlineHomeFeed>, val fetchedAt: Long, val historyThrough: Long = fetchedAt,
    val id: String = java.util.UUID.randomUUID().toString()) {
    val feed: OnlineHomeFeed get() = pages.reduceOrNull(::appendCollectionPage) ?: OnlineHomeFeed()
    fun forDisk() = copy(pages = pages.take(3))
}

@Serializable
data class QuickPickBatch(val tracks: List<OnlineTrack>, val fetchedAt: Long)

@Serializable
data class HomeExposure(val seenAt: Long, val session: Long)

@Serializable
data class HomeCacheRecord(
    val namespace: String,
    val version: Int = 1,
    val active: HomeFeedSnapshot? = null,
    val pending: HomeFeedSnapshot? = null,
    val candidates: List<QuickPickBatch> = emptyList(),
    val exposures: Map<String, HomeExposure> = emptyMap(),
    val session: Long = 0,
    val lastAutomaticAttempt: Long? = null,
)

interface HomeFeedCache {
    suspend fun read(namespace: String): HomeCacheRecord?
    suspend fun write(record: HomeCacheRecord)
    suspend fun clear()
}

/** One active account only; same-directory atomic replacement never exposes a partial JSON document. */
@Singleton
class AtomicHomeFeedCache internal constructor(private val directory: File) : HomeFeedCache {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "home_feed"))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(directory, "snapshot.json")
    override suspend fun read(namespace: String): HomeCacheRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (!file.exists() || file.length() > MAX_BYTES) return@withLock null
                json.decodeFromString<HomeCacheRecord>(file.readText(Charsets.UTF_8))
                    .takeIf { it.version == 1 && it.namespace == namespace }
            }.getOrNull()
        }
    }
    override suspend fun write(record: HomeCacheRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bounded = record.copy(active = record.active?.forDisk(), pending = record.pending?.forDisk(),
                candidates = boundedQuickPickBatches(record.candidates),
                exposures = record.exposures.entries.sortedByDescending { it.value.seenAt }.take(256).associate { it.toPair() })
            val bytes = json.encodeToString(bounded).toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_BYTES) { "Home snapshot exceeds cache budget" }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create Home cache" }
            val temporary = File(directory, "snapshot.tmp")
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            Unit
        }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { file.delete(); File(directory, "snapshot.tmp").delete(); Unit }
    }
    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}

internal fun OnlineHomeFeed.isUsable() = tracks.isNotEmpty() || shelves.any { it.items.isNotEmpty() }
internal fun quickPickCandidates(feed: OnlineHomeFeed): List<OnlineTrack> =
    (feed.shelves.firstOrNull { it.title.trim().equals("Quick picks", true) }?.items?.mapNotNull { it.track }
        ?: feed.tracks).filter { Regex("[A-Za-z0-9_-]{11}").matches(it.videoId) && it.title.isNotBlank() }.distinctBy { it.videoId }.take(64)

internal fun boundedQuickPickBatches(batches: List<QuickPickBatch>): List<QuickPickBatch> {
    val ids = mutableSetOf<String>()
    return batches.take(2).map { batch -> batch.copy(tracks = batch.tracks.filter { ids.size < 64 && ids.add(it.videoId) }) }
        .filter { it.tracks.isNotEmpty() }
}

internal fun selectQuickPicks(batches: List<QuickPickBatch>, exposures: Map<String, HomeExposure>,
    recentlyPlayed: Set<String>, session: Long, now: Long): List<OnlineTrack> {
    val candidates = batches.filter { now - it.fetchedAt in 0..48 * 60 * 60_000L }
        .take(2).flatMap { it.tracks }.filter { Regex("[A-Za-z0-9_-]{11}").matches(it.videoId) && it.title.isNotBlank() }
        .distinctBy { it.videoId }.take(64)
    val ranks = candidates.mapIndexed { index, track -> track.videoId to index }.toMap()
    return candidates.sortedWith(compareBy<OnlineTrack>(
        { if ((exposures[it.videoId]?.session ?: Long.MIN_VALUE) >= session - 2) 1 else 0 },
        { if ("youtube:${it.videoId}" in recentlyPlayed) 1 else 0 },
        { exposures[it.videoId]?.seenAt ?: 0L },
        { ranks[it.videoId] ?: Int.MAX_VALUE },
    )).take(16)
}
