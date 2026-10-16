package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.model.SearchFilterType
import kotlinx.serialization.json.*

data class OnlineSearchCollection(val type: SearchFilterType, val item: HomeFeedItem)
data class OnlineSearchResults(val tracks: List<OnlineTrack> = emptyList(),
    val collections: List<OnlineSearchCollection> = emptyList())

internal fun musicSearchParams(filter: SearchFilterType): String? = when (filter) {
    SearchFilterType.ALL -> null
    SearchFilterType.SONGS -> "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    SearchFilterType.ALBUMS -> "EgWKAQIYAWoMEA4QChADEAQQCRAF"
    SearchFilterType.ARTISTS -> "EgWKAQIgAWoMEA4QChADEAQQCRAF"
    SearchFilterType.PLAYLISTS -> "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D"
}

/** Classify the card's own target, never an artist/album link in its metadata or menu. */
internal fun parseMusicSearch(root: JsonElement, filter: SearchFilterType): OnlineSearchResults {
    val collections = linkedMapOf<String, OnlineSearchCollection>()
    val tracks = linkedMapOf<String, OnlineTrack>()
    fun visit(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val kind = listOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "musicCardShelfRenderer")
                    .firstOrNull { node[it] is JsonObject }
                if (kind != null) {
                    val card = node[kind] as JsonObject
                    val wrapper = if (kind == "musicCardShelfRenderer") buildJsonObject { put("musicTwoRowItemRenderer", card) } else node
                    val item = OnlineHomeFeedParser.item(wrapper)
                    if (item != null) {
                        val id = item.browseId.orEmpty()
                        val pageType = (card["navigationEndpoint"] ?: card["onTap"])
                            ?.let { OnlineCatalogParser.firstValue(it, "pageType") }
                        val type = when {
                            id.startsWith("MPRE") || pageType == "MUSIC_PAGE_TYPE_ALBUM" -> SearchFilterType.ALBUMS
                            pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" -> null
                            id.startsWith("UC") || id.startsWith("MPLA") -> SearchFilterType.ARTISTS
                            id.startsWith("VL") || id.startsWith("RD") || id.startsWith("VM") || item.playlistId != null -> SearchFilterType.PLAYLISTS
                            else -> null
                        }
                        if (type != null && (filter == SearchFilterType.ALL || filter == type)) {
                            collections.putIfAbsent(item.key, OnlineSearchCollection(type, item))
                        } else if (item.track != null && filter in setOf(SearchFilterType.ALL, SearchFilterType.SONGS)) {
                            val videoType = OnlineCatalogParser.firstValue(card, "musicVideoType")
                            if (videoType == null || videoType == "MUSIC_VIDEO_TYPE_ATV") tracks.putIfAbsent(item.track.videoId, item.track)
                        }
                    }
                    // Top-result cards may also contain a direct list of further search results.
                    if (kind == "musicCardShelfRenderer") card["contents"]?.let(::visit)
                    return
                }
                node.filterKeys { it !in setOf("menu", "header", "trackingParams", "frameworkUpdates") }.values.forEach(::visit)
            }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        }
    }
    // Search only the content tree, excluding chips/suggestions and navigation commands.
    (root as? JsonObject)?.get("contents")?.let(::visit)
    return OnlineSearchResults(tracks.values.take(80), collections.values.take(80))
}
