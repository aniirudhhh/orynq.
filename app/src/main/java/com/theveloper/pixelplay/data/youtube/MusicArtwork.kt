package com.theveloper.pixelplay.data.youtube

import java.text.Normalizer
import java.util.Locale

internal fun isMusicAlbumArtwork(url: String): Boolean =
    runCatching { java.net.URI(url).host }.getOrNull() in
        setOf("lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com")

/** Borrow only artwork, never change the selected video or its playback identity. */
internal fun matchingMusicArtwork(track: OnlineTrack, candidates: List<OnlineTrack>): String? {
    fun words(value: String): List<String> = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^\\p{L}\\p{N}]+")) .filter(String::isNotBlank)
    fun title(value: String) = words(value.substringBefore('|')
        .replace(Regex("(?i)\\((?:official (?:music )?video|official audio|lyrics?|audio|video)\\)"), ""))
    val wantedTitle = title(track.title)
    val wantedArtist = words(track.artist).filterNot { it in setOf("official", "topic", "music", "youtube") }.toSet()
    return candidates.firstOrNull { candidate ->
        candidate.artwork?.let(::isMusicAlbumArtwork) == true &&
            (candidate.videoId == track.videoId ||
                (wantedTitle.isNotEmpty() && title(candidate.title) == wantedTitle &&
                    wantedArtist.isNotEmpty() && wantedArtist.intersect(words(candidate.artist).toSet()).size >=
                        minOf(2, wantedArtist.size) &&
                    (track.durationMs <= 0 || candidate.durationMs <= 0 ||
                        kotlin.math.abs(track.durationMs - candidate.durationMs) <= 15_000L)))
    }?.artwork?.let(::highResolutionYouTubeArtwork)
}
