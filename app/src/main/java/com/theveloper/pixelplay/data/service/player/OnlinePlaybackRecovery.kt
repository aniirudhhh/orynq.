package com.theveloper.pixelplay.data.service.player

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

internal data class RecoveryPlayback(val mediaId: String? = null, val positionMs: Long = 0,
    val wantsPlay: Boolean = false, val playing: Boolean = false, val localPlayer: Boolean = true)

/** Service-owned, Main-thread recovery. Never persists autoplay intent across process restarts. */
internal class OnlinePlaybackRecovery(private val scope: CoroutineScope, private val online: StateFlow<Boolean>,
    private val playback: () -> RecoveryPlayback, private val retry: (RecoveryPlayback) -> Unit,
    private val expired: () -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {
    private var target: String? = null
    private var budgetKey: String? = null
    private var attempts = 0
    private var deadline = 0L
    private var retryJob: Job? = null
    private var stableJob: Job? = null
    private var expirationJob: Job? = null
    private val connectionJob = scope.launch {
        online.collect { connected ->
            if (connected) schedule() else { retryJob?.cancel(); retryJob = null }
        }
    }

    fun failed(recoverableNetworkError: Boolean): Boolean {
        val current = playback()
        val id = current.mediaId ?: return false
        if (!recoverableNetworkError || !id.startsWith("youtube:") || !current.wantsPlay || !current.localPlayer) return false
        if (budgetKey != id) {
            clear(true)
            budgetKey = id
            deadline = now() + MAX_WAIT
        }
        if (attempts >= 3 || now() >= deadline) { clear(false); return false }
        target = id
        stableJob?.cancel()
        if (expirationJob?.isActive != true) expirationJob = scope.launch {
            delay((deadline - now()).coerceAtLeast(1))
            if (target != null) { clear(false); expired() }
        }
        schedule()
        return true
    }

    private fun schedule() {
        val id = target ?: return
        if (!online.value || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(listOf(500L, 2_000L, 5_000L)[attempts.coerceAtMost(2)])
            val current = playback()
            if (current.mediaId != id || !current.wantsPlay || !current.localPlayer) { clear(true); return@launch }
            if (!online.value) return@launch
            if (now() >= deadline || attempts >= 3) { clear(false); expired(); return@launch }
            if (current.playing) { playingChanged(true); return@launch }
            attempts++
            try { retry(current.copy(positionMs = current.positionMs.coerceAtLeast(0))) }
            catch (_: Exception) { clear(false); expired() }
        }
    }

    fun playingChanged(playing: Boolean) {
        stableJob?.cancel()
        if (!playing) return
        val current = playback()
        if (current.mediaId != budgetKey) return
        target = null
        retryJob?.cancel(); retryJob = null
        expirationJob?.cancel(); expirationJob = null
        stableJob = scope.launch {
            delay(15_000)
            val latest = playback()
            if (latest.mediaId == current.mediaId && latest.playing && latest.wantsPlay) clear(true)
        }
    }
    fun intentChanged(wantsPlay: Boolean) { if (!wantsPlay) clear(true) }
    fun trackChanged() = clear(true)
    fun stopped() = clear(true)
    fun close() { clear(true); connectionJob.cancel() }
    private fun clear(resetBudget: Boolean) {
        target = null
        retryJob?.cancel(); retryJob = null
        stableJob?.cancel(); stableJob = null
        expirationJob?.cancel(); expirationJob = null
        if (resetBudget) { budgetKey = null; attempts = 0; deadline = 0 }
    }
    companion object { internal const val MAX_WAIT = 30 * 60_000L }
}
