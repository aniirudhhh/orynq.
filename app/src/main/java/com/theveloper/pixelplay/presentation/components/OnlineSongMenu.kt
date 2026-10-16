package com.theveloper.pixelplay.presentation.components

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel

/** Retains the originating queue even if the collection is dismissed or the feed refreshes. */
internal data class OnlineSongMenuRequest(val song: Song, val play: () -> Unit)

@Composable
internal fun OnlineSongMenu(
    request: OnlineSongMenuRequest,
    player: PlayerViewModel,
    onDismiss: () -> Unit,
    onArtist: (Song) -> Unit,
    currentPlaylistId: String? = null,
    onAlbum: (Song) -> Unit = {},
    onGenre: (Song) -> Unit = {}
) {
    key(request.song.id) {
        val selected = remember { request }
        val favorites by player.favoriteSongIds.collectAsStateWithLifecycle()
        val playback by player.stablePlayerState.collectAsStateWithLifecycle()
        val playlists: PlaylistViewModel = hiltViewModel()
        val playlistState by playlists.uiState.collectAsStateWithLifecycle()
        var choosingPlaylist by remember { mutableStateOf(false) }
        if (choosingPlaylist) {
            PlaylistBottomSheet(playlistState, listOf(selected.song), onDismiss, 0.dp, player,
                playlistViewModel = playlists, currentPlaylistId = currentPlaylistId)
        } else {
            SongInfoBottomSheet(
                song = selected.song,
                isFavorite = selected.song.id in favorites,
                onToggleFavorite = { player.toggleFavoriteSpecificSong(selected.song) },
                isShuffleEnabled = playback.isShuffleEnabled,
                repeatMode = playback.repeatMode,
                onToggleShuffle = { player.toggleShuffle() },
                onCycleRepeat = player::cycleRepeatMode,
                onDismiss = onDismiss,
                onPlaySong = { selected.play(); onDismiss() },
                onAddToQueue = { player.addSongToQueue(selected.song); onDismiss() },
                onAddNextToQueue = { player.addSongNextToQueue(selected.song); onDismiss() },
                onAddToPlayList = { choosingPlaylist = true },
                onDeleteFromDevice = player::deleteFromDevice,
                onNavigateToAlbum = { onDismiss(); onAlbum(selected.song) },
                onNavigateToArtist = { onDismiss(); onArtist(selected.song) },
                onNavigateToGenre = { onDismiss(); onGenre(selected.song) },
                onEditSong = { title, artist, album, albumArtist, composer, genre, lyrics, track, disc, trackGain, albumGain, cover ->
                    player.editSongMetadata(selected.song, title, artist, album, albumArtist, composer,
                        genre, lyrics, track, disc, trackGain, albumGain, cover)
                },
                removeFromListTrigger = {}
            )
        }
    }
}
