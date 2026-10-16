package com.theveloper.pixelplay.data.youtube

import android.content.Context
import com.theveloper.pixelplay.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ListeningSyncTicket(val namespace: String, val revision: String, val videoId: String,
    val id: String = UUID.randomUUID().toString())
@Serializable
data class PendingListen(val ticket: ListeningSyncTicket, val listenedMs: Long, val createdAt: Long,
    val attempts: Int = 0, val retryAt: Long = 0, val claimed: Boolean = false)
@Serializable
data class ListeningSyncRecord(val namespace: String, val enabled: Boolean = false,
    val revision: String = UUID.randomUUID().toString(), val queue: List<PendingListen> = emptyList(),
    val completed: List<String> = emptyList(), val lastAcceptedAt: Long? = null,
    val notice: String? = null, val authBlocked: Boolean = false)
data class ListeningSyncState(val ready: Boolean = false, val enabled: Boolean = false,
    val pending: Int = 0, val lastAcceptedAt: Long? = null, val notice: String? = null, val waitingForInternet: Boolean = false)
internal enum class ListenReport { ACCEPTED, RETRY, RECONNECT, UNAVAILABLE, UNCONFIRMED, OFFLINE }
internal interface ListeningSyncSource {
    val namespace: StateFlow<String>
    suspend fun report(event: PendingListen, beforeSend: suspend () -> Boolean): ListenReport
}
internal interface ListeningSyncStore {
    suspend fun read(): ListeningSyncRecord?
    suspend fun write(record: ListeningSyncRecord)
}

@Singleton
class AtomicListeningSyncStore internal constructor(private val directory: File) : ListeningSyncStore {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "listening_sync"))
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(directory, "state.json")
    override suspend fun read(): ListeningSyncRecord? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists() || file.length() > 256_000) return@runCatching null
            json.decodeFromString<ListeningSyncRecord>(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }
    override suspend fun write(record: ListeningSyncRecord) = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val temp = File(directory, "state.tmp")
        try {
            val bytes = json.encodeToString(record).toByteArray(Charsets.UTF_8)
            check(bytes.size <= 256_000)
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        Unit
    }
}

private class AccountListeningSyncSource(private val repository: YouTubeRepository) : ListeningSyncSource {
    override val namespace = repository.homeCacheNamespace
    override suspend fun report(event: PendingListen, beforeSend: suspend () -> Boolean) =
        repository.reportListen(event, beforeSend)
}

/** New sessions only. No scan, import, polling or replay of local playback history. */
@Singleton
class YouTubeListeningSync internal constructor(private val source: ListeningSyncSource,
    private val store: ListeningSyncStore, private val scope: CoroutineScope,
    private val online: StateFlow<Boolean> = MutableStateFlow(true),
    private val now: () -> Long = System::currentTimeMillis) {
    @Inject constructor(repository: YouTubeRepository, store: AtomicListeningSyncStore,
        @AppScope scope: CoroutineScope, internet: com.theveloper.pixelplay.data.network.ValidatedInternet) :
        this(AccountListeningSyncSource(repository), store, scope, internet.online)
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(ListeningSyncState())
    val state = mutableState.asStateFlow()
    @Volatile private var authority: ListeningSyncRecord? = null
    private var record = ListeningSyncRecord("")
    @Volatile private var attempt: Deferred<ListenReport>? = null
    private val mutations = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var requestedRevision: String? = null

    init {
        scope.launch {
            var previous = online.value
            online.collect { connected ->
                mutex.withLock {
                    if (connected && !previous && authority != null && record.queue.isNotEmpty()) {
                        record = record.copy(queue = record.queue.map { if (it.claimed) it else it.copy(retryAt = 0) })
                        save()
                    } else mutableState.value = mutableState.value.copy(waitingForInternet = waitingForInternet())
                    previous = connected
                }
                wake.trySend(Unit)
            }
        }
        scope.launch {
            source.namespace.collectLatest { namespace ->
                mutations.incrementAndGet()
                requestedRevision = null
                authority = null
                attempt?.cancel()
                mutex.withLock {
                    val saved = try { store.read() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    record = saved?.takeIf { it.namespace == namespace && namespace != "anonymous" }
                        ?: ListeningSyncRecord(namespace)
                    val abandoned = record.queue.filter { it.claimed }
                    record = record.copy(queue = record.queue.filter { !it.claimed && valid(it) }.takeLast(100),
                        completed = (record.completed + abandoned.map { it.ticket.id }).takeLast(256),
                        notice = if (abandoned.isNotEmpty()) "unconfirmed" else record.notice)
                    save()
                }
                wake.trySend(Unit)
            }
        }
        scope.launch {
            while (isActive) {
                wake.receive()
                while (isActive) {
                    val event = mutex.withLock {
                        val retained = record.queue.filter(::valid)
                        if (retained.size != record.queue.size) {
                            record = record.copy(queue = retained)
                            save()
                        }
                        if (authority == null || !record.enabled || record.authBlocked || !online.value) null
                        else record.queue.firstOrNull { valid(it) && !it.claimed }
                    } ?: break
                    val wait = event.retryAt - now()
                    if (wait > 0) {
                        withTimeoutOrNull(wait) { wake.receive() }
                        continue
                    }
                    var deferredOffline = false
                    val task = scope.async(start = CoroutineStart.LAZY) {
                        source.report(event) {
                            if (!online.value) { deferredOffline = true; false }
                            else {
                                val claimed = claim(event)
                                if (claimed && !online.value) { deferredOffline = true; false } else claimed
                            }
                        }
                    }
                    attempt = task
                    val result = try { task.await() }
                    catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        continue
                    } catch (_: Exception) {
                        mutex.withLock {
                            if (record.queue.any { it.ticket.id == event.ticket.id && it.claimed }) ListenReport.UNCONFIRMED
                            else ListenReport.RETRY
                        }
                    } finally { if (attempt === task) attempt = null }
                    mutex.withLock {
                        if (record.revision != event.ticket.revision || record.namespace != event.ticket.namespace) return@withLock
                        val claimed = record.queue.any { it.ticket.id == event.ticket.id && it.claimed }
                        val outcome = if (deferredOffline || (!claimed && !online.value && result == ListenReport.RETRY))
                            ListenReport.OFFLINE else result
                        val retry = outcome == ListenReport.OFFLINE || (outcome == ListenReport.RETRY && event.attempts < 2 && valid(event))
                        val replacement = event.copy(attempts = event.attempts + if (outcome == ListenReport.OFFLINE) 0 else 1,
                            retryAt = if (outcome == ListenReport.OFFLINE) 0 else now() + if (event.attempts == 0) 30_000 else 120_000,
                            claimed = false)
                        record = record.copy(
                            queue = record.queue.filterNot { it.ticket.id == event.ticket.id }.let {
                                if (retry) it + replacement else it
                            },
                            completed = if (retry) record.completed else (record.completed + event.ticket.id).takeLast(256),
                            lastAcceptedAt = if (outcome == ListenReport.ACCEPTED) now() else record.lastAcceptedAt,
                            authBlocked = outcome == ListenReport.RECONNECT,
                            notice = when (outcome) {
                                ListenReport.OFFLINE -> "offline"
                                ListenReport.ACCEPTED -> null
                                ListenReport.RECONNECT -> "reconnect"
                                ListenReport.UNCONFIRMED -> "unconfirmed"
                                ListenReport.UNAVAILABLE -> "unavailable"
                                ListenReport.RETRY -> if (retry) "retry" else "failed"
                            })
                        save()
                    }
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        // Revoke ticket issuance immediately; disk work stays off the playback/UI thread.
        val mutation = mutations.incrementAndGet()
        val revision = UUID.randomUUID().toString()
        requestedRevision = revision
        authority = null
        attempt?.cancel()
        scope.launch {
            mutex.withLock {
                if (mutation != mutations.get()) return@withLock
                if (record.namespace != source.namespace.value || record.namespace == "anonymous") return@withLock
                record = record.copy(enabled = enabled, revision = revision,
                    queue = emptyList(), completed = emptyList(), notice = null, authBlocked = false)
                save()
            }
            wake.trySend(Unit)
        }
    }

    fun begin(songId: String): ListeningSyncTicket? {
        val current = authority ?: return null
        val id = songId.removePrefix("youtube:")
        if (!current.enabled || current.authBlocked || current.namespace != source.namespace.value ||
            !songId.startsWith("youtube:") || !Regex("[A-Za-z0-9_-]{11}").matches(id)) return null
        return ListeningSyncTicket(current.namespace, current.revision, id)
    }

    fun complete(ticket: ListeningSyncTicket?, listenedMs: Long) {
        if (ticket == null || listenedMs < 30_000) return
        scope.launch {
            mutex.withLock {
                if (!record.enabled || ticket.namespace != source.namespace.value || record.namespace != ticket.namespace ||
                    record.revision != ticket.revision || ticket.id in record.completed || record.queue.any { it.ticket.id == ticket.id }) return@withLock
                record = record.copy(queue = (record.queue.filter(::valid) + PendingListen(ticket, listenedMs, now())).takeLast(100))
                save()
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun claim(event: PendingListen): Boolean = mutex.withLock {
        if (authority == null || !record.enabled || record.namespace != source.namespace.value ||
            record.namespace != event.ticket.namespace || record.revision != event.ticket.revision) return@withLock false
        val index = record.queue.indexOfFirst { it.ticket.id == event.ticket.id && !it.claimed }
        if (index < 0) return@withLock false
        record = record.copy(queue = record.queue.toMutableList().also { it[index] = it[index].copy(claimed = true) })
        save() // Persist the send boundary first. Uncertain/crashed sends are never blindly replayed.
    }

    private fun valid(event: PendingListen) = event.listenedMs >= 30_000 && event.attempts in 0..2 &&
        event.ticket.namespace == record.namespace && event.ticket.revision == record.revision &&
        Regex("[A-Za-z0-9_-]{11}").matches(event.ticket.videoId) && now() - event.createdAt in 0L..86_400_000L

    private fun waitingForInternet() = record.enabled && !record.authBlocked && record.queue.isNotEmpty() && !online.value

    private suspend fun save(): Boolean {
        return try {
            store.write(record)
            authority = record.takeIf { it.namespace == source.namespace.value &&
                (requestedRevision == null || requestedRevision == it.revision) }
            mutableState.value = ListeningSyncState(true, record.enabled, record.queue.size, record.lastAcceptedAt, record.notice, waitingForInternet())
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            authority = null
            mutableState.value = ListeningSyncState(ready = false, notice = "storage")
            false
        }
    }
}
