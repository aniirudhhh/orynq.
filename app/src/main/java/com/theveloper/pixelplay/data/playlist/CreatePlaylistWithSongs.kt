package com.theveloper.pixelplay.data.playlist

import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.youtube.YouTubeRepository
import com.theveloper.pixelplay.data.youtube.onlineVideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CreatePlaylistWithSongs @Inject constructor(
    private val playlists: PlaylistPreferencesRepository,
    private val youtube: YouTubeRepository
) {
    suspend operator fun invoke(name: String, songs: List<Song>): Playlist = withContext(Dispatchers.IO) {
        val title = name.trim()
        require(title.isNotBlank()) { "Enter a playlist name." }
        val selected = songs.distinctBy { it.id }
        require(selected.isNotEmpty() && selected.none { it.id.isBlank() }) { "Select at least one song." }
        require(selected.none { it.id.startsWith("youtube:") && onlineVideoId(it) == null }) { "Invalid online song." }
        // Membership is created only after all streaming metadata is safely retained.
        selected.filter { onlineVideoId(it) != null }.forEach(youtube::retainPlaylistSong)
        playlists.createPlaylist(name = title, songIds = selected.map { it.id })
    }
}
