package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.model.Song

internal fun onlineVideoId(song: Song): String? = song.id.takeIf { it.startsWith("youtube:") }
    ?.removePrefix("youtube:")?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }

internal fun musicShareUrl(song: Song): String? = onlineVideoId(song)?.let { "https://music.youtube.com/watch?v=$it" }

internal fun resolveOnlinePlaylistSongs(ids: List<String>, local: List<Song>, online: List<OnlineTrack>): List<Song> {
    val byId = local.associateBy { it.id } + online.associate { "youtube:${it.videoId}" to it.asSong() }
    return ids.mapNotNull { byId[it] }
}

internal fun queueWithAddedSong(original: List<Song>, song: Song, currentId: String?, playNext: Boolean): List<Song> {
    val queue = original.toMutableList()
    val current = queue.indexOfFirst { it.id == currentId }
    queue.add(if (playNext && current >= 0) current + 1 else queue.size, song)
    return queue
}
