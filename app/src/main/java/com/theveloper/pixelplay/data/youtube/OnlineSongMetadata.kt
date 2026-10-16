package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.*

data class OnlineSongMetadata(val durationMs: Long? = null, val album: String? = null) {
    fun merge(other: OnlineSongMetadata) = OnlineSongMetadata(durationMs ?: other.durationMs, album ?: other.album)
}

private fun JsonElement?.scalarText() = (this as? JsonPrimitive)?.contentOrNull

internal fun musicDurationMs(value: String): Long? {
    val token = Regex("(?<![\\d:])(?:\\d+:)?\\d+:\\d{2}(?![\\d:])").find(value)?.value ?: return null
    val parts = token.split(':').map { it.toLongOrNull() ?: return null }
    if (parts.first() > 10080) return null
    if (parts.drop(1).any { it !in 0..59 }) return null
    return parts.fold(0L) { total, part -> total * 60 + part }.times(1000).takeIf { it > 0 }
}

internal fun musicAlbum(root: JsonElement): String? {
    return when (root) {
        is JsonObject -> {
            val browse = (root["navigationEndpoint"] as? JsonObject)?.get("browseEndpoint") as? JsonObject
            val albumTarget = browse?.get("browseId")?.scalarText()?.startsWith("MPRE") == true ||
                browse?.toString()?.contains("MUSIC_PAGE_TYPE_ALBUM") == true
            (if (albumTarget) root["text"]?.scalarText()?.takeIf(String::isNotBlank) else null)
                ?: root.values.firstNotNullOfOrNull(::musicAlbum)
        }
        is JsonArray -> root.firstNotNullOfOrNull(::musicAlbum)
        else -> null
    }
}

/** Inspect only the requested video's details/current panel row and its watch metadata. */
internal fun parseOnlineSongMetadata(root: JsonElement, videoId: String): OnlineSongMetadata {
    var result = OnlineSongMetadata()
    fun visit(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val details = node["videoDetails"] as? JsonObject
                if (details?.get("videoId")?.scalarText() == videoId) {
                    val seconds = details["lengthSeconds"]?.scalarText()?.toLongOrNull()
                    result = result.merge(OnlineSongMetadata(seconds?.takeIf { it in 1..604800 }?.times(1000)))
                }
                val panel = node["playlistPanelVideoRenderer"] as? JsonObject
                if (panel?.get("videoId")?.scalarText() == videoId) {
                    result = result.merge(OnlineSongMetadata(musicDurationMs(OnlineCatalogParser.text(panel["lengthText"])), musicAlbum(panel)))
                }
                (node["musicWatchMetadataRenderer"] as? JsonObject)?.let {
                    result = result.merge(OnlineSongMetadata(album = musicAlbum(it)))
                }
                node.values.forEach(::visit)
            }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return result
}
