package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import com.theveloper.pixelplay.data.youtube.OnlineDailyMixManager
import com.theveloper.pixelplay.data.youtube.YouTubeRepository
import com.theveloper.pixelplay.data.youtube.OnlineTrack
import com.theveloper.pixelplay.data.youtube.mixDay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Daily Mix and Your Mix state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Generate and update daily/your mixes
 * - Persist and restore mix state
 * - Check if mix needs updating based on day change
 */
@Singleton
class DailyMixStateHolder @Inject constructor(
    private val dailyMixManager: DailyMixManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val onlineMixManager: OnlineDailyMixManager,
    private val youtubeRepository: YouTubeRepository,
    private val listeningStatsTracker: ListeningStatsTracker
) {
    private var scope: CoroutineScope? = null
    private var updateJob: Job? = null
    private var historyJob: Job? = null
    private var generation = 0L
    private val _isUpdating = MutableStateFlow(false)
    val isUpdating = _isUpdating.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _dailyMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val dailyMixSongs: StateFlow<ImmutableList<Song>> = _dailyMixSongs.asStateFlow()

    private val _yourMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val yourMixSongs: StateFlow<ImmutableList<Song>> = _yourMixSongs.asStateFlow()

    /**
     * Initialize with coroutine scope from ViewModel.
     */
    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        historyJob?.cancel()
        historyJob = coroutineScope.launch {
            listeningStatsTracker.playbackHistory.collect { history ->
                if (history.any { it.songId.startsWith("youtube:") && it.listenedMs >= 30_000 }) {
                    updateDailyMix(musicRepository.getFavoriteSongIdsFlow())
                }
            }
        }
    }

    /**
     * Remove a song from the daily mix.
     */
    fun removeFromDailyMix(songId: String) {
        _dailyMixSongs.update { currentList ->
            currentList.filterNot { it.id == songId }.toImmutableList()
        }
        if (songId.startsWith("youtube:")) {
            onlineMixManager.save(_dailyMixSongs.value.map {
                OnlineTrack(it.id.removePrefix("youtube:"), it.title, it.artist, it.albumArtUriString, it.duration)
            })
        }
    }

    /**
     * Update the daily mix with new songs.
     * Uses getAllSongsOnce() to load songs on-demand instead of keeping a permanent subscription.
     */
    fun updateDailyMix(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>, forceOnline: Boolean = false) {
        val requestGeneration = ++generation
        updateJob?.cancel()
        updateJob = scope?.launch(Dispatchers.IO) {
            _isUpdating.value = true
            _error.value = null
            try {
                val onlineMix = onlineMixManager.generate(listeningStatsTracker.playbackHistory.value, forceOnline)
                if (!onlineMix.isNullOrEmpty()) {
                    _dailyMixSongs.value = onlineMix.map { it.asSong() }.toImmutableList()
                    userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
                    return@launch
                }
                val allSongs = musicRepository.getAllSongsOnce()
                if (allSongs.isNotEmpty()) {
                    val favoriteIds = favoriteSongIdsFlow.first()
                    val mix = dailyMixManager.generateDailyMix(allSongs, favoriteIds)
                    _dailyMixSongs.value = mix.toImmutableList()
                    userPreferencesRepository.saveDailyMixSongIds(mix.map { it.id })
                    val yourMix = dailyMixManager.generateYourMix(allSongs, favoriteIds)
                    _yourMixSongs.value = yourMix.toImmutableList()
                    userPreferencesRepository.saveYourMixSongIds(yourMix.map { it.id })
                    if (mix.isNotEmpty()) userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
                } else _yourMixSongs.value = persistentListOf()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _error.value = "Could not refresh Daily Mix. Your previous mix is still available." }
            finally { if (generation == requestGeneration) _isUpdating.value = false }
        }
    }

    /**
     * Load persisted daily mix from storage using direct DB queries by IDs
     * instead of combining with the full allSongs flow.
     */
    fun loadPersistedDailyMix() {
        val online = onlineMixManager.cached()
        if (online.isNotEmpty()) _dailyMixSongs.value = online.map { it.asSong() }.toImmutableList()
        // Load Daily Mix
        scope?.launch {
            val dailyMixIds = userPreferencesRepository.dailyMixSongIdsFlow.first()
            if (dailyMixIds.isNotEmpty() && _dailyMixSongs.value.isEmpty()) {
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getSongsByIds(dailyMixIds).first()
                }
                if (songs.isNotEmpty()) {
                    // Maintain persisted order
                    val songMap = songs.associateBy { it.id }
                    val orderedSongs = dailyMixIds.mapNotNull { songMap[it] }
                    _dailyMixSongs.value = orderedSongs.toImmutableList()
                }
            }
        }

        // Load Your Mix
        scope?.launch {
            val yourMixIds = userPreferencesRepository.yourMixSongIdsFlow.first()
            if (yourMixIds.isNotEmpty() && _yourMixSongs.value.isEmpty()) {
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getSongsByIds(yourMixIds).first()
                }
                if (songs.isNotEmpty()) {
                    val songMap = songs.associateBy { it.id }
                    val orderedSongs = yourMixIds.mapNotNull { songMap[it] }
                    _yourMixSongs.value = orderedSongs.toImmutableList()
                }
            }
        }
    }

    /**
     * Force update the daily mix regardless of day.
     */
    fun forceUpdate(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        updateDailyMix(favoriteSongIdsFlow, forceOnline = true)
    }

    /**
     * Check if daily mix needs updating (new day) and update if so.
     */
    fun checkAndUpdateIfNeeded(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        scope?.launch {
            val lastUpdate = userPreferencesRepository.lastDailyMixUpdateFlow.first()
            if (_dailyMixSongs.value.isEmpty() || mixDay(System.currentTimeMillis()) != mixDay(lastUpdate)) {
                updateDailyMix(favoriteSongIdsFlow)
            }
        }
    }

    /**
     * Set the daily mix songs directly (used for AI-generated mixes).
     */
    fun setDailyMixSongs(songs: List<Song>) {
        _dailyMixSongs.value = songs.toImmutableList()
        scope?.launch {
            userPreferencesRepository.saveDailyMixSongIds(songs.map { it.id })
        }
    }

    /**
     * Get a candidate pool for AI playlist generation.
     */
    suspend fun getCandidatePool(
        allSongs: List<Song>,
        favoriteIds: Set<String>,
        maxSize: Int = 100
    ): List<Song> {
        return dailyMixManager.generateDailyMix(allSongs, favoriteIds, maxSize)
    }

    fun onCleared() {
        updateJob?.cancel()
        historyJob?.cancel()
        scope = null
    }
}
