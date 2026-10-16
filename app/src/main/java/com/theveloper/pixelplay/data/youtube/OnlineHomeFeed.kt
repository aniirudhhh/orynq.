package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class HomeShelfLayout { CARDS, ROWS, GRID }

@Serializable
data class HomeFeedItem(
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    val track: OnlineTrack? = null,
    val browseId: String? = null,
    val params: String? = null,
    val playlistId: String? = null,
) {
    val key: String get() = track?.let { "song:${it.videoId}" } ?: "browse:$browseId:$params:$playlistId"
}

@Serializable
data class HomeFeedShelf(val id: String, val title: String, val layout: HomeShelfLayout, val items: List<HomeFeedItem>)

@Serializable
data class OnlineHomeFeed(val shelves: List<HomeFeedShelf> = emptyList(), val tracks: List<OnlineTrack> = emptyList(),
    val continuation: String? = null)

/** Read direct shelf cards only. Menu actions must never become playable feed entries. */
internal object OnlineHomeFeedParser {
    fun parseCollection(root: JsonElement): OnlineHomeFeed {
        fun find(node: JsonElement, kinds: Set<String>): JsonObject? = when (node) {
            is JsonObject -> kinds.firstNotNullOfOrNull { node[it] as? JsonObject }
                ?: node.filterKeys { it !in setOf("menu", "header") }.values.firstNotNullOfOrNull { find(it, kinds) }
            is JsonArray -> node.firstNotNullOfOrNull { find(it, kinds) }
            else -> null
        }
        val playlist = find(root, setOf("musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation", "playlistPanelRenderer"))
        val primary = playlist ?: find(root, setOf("musicShelfRenderer", "musicShelfContinuation"))
        if (primary != null) {
            val token = primary["continuations"]?.let { OnlineCatalogParser.firstValue(it, "continuation") }
            return OnlineHomeFeed(tracks = collectionTracks(primary), continuation = token)
        }
        return parse(root)
    }

    fun parse(root: JsonElement, homePage: Boolean = false): OnlineHomeFeed {
        val shelves = mutableListOf<HomeFeedShelf>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val shelf = listOf("musicCarouselShelfRenderer", "musicShelfRenderer", "musicGridRenderer", "gridRenderer")
                        .firstNotNullOfOrNull { key -> (node[key] as? JsonObject)?.let { key to it } }
                    if (shelf != null) {
                        val (kind, body) = shelf
                        val cards = (body["contents"] ?: body["items"]) as? JsonArray
                        val entries = cards.orEmpty().mapNotNull(::item).distinctBy { it.key }
                        val header = (body["header"] as? JsonObject)?.values?.firstOrNull() as? JsonObject
                        val title = OnlineCatalogParser.text(header?.get("title") ?: body["title"])
                        if (title.isNotBlank() && entries.isNotEmpty()) {
                            val rows = cards.orEmpty().any { (it as? JsonObject)?.containsKey("musicResponsiveListItemRenderer") == true }
                            val grid = kind in setOf("musicGridRenderer", "gridRenderer") ||
                                cards.orEmpty().any { (it as? JsonObject)?.containsKey("musicMultiRowListItemRenderer") == true }
                            shelves += HomeFeedShelf("${shelves.size}:$title", title,
                                if (grid) HomeShelfLayout.GRID else if (rows) HomeShelfLayout.ROWS else HomeShelfLayout.CARDS, entries)
                        }
                        return
                    }
                    // The home tab's content tree contains recommendations; headers/menus do not.
                    node.filterKeys { it !in setOf("menu", "header", "trackingParams", "frameworkUpdates") }.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        val tracks = shelves.flatMap { it.items }.mapNotNull { it.track }.distinctBy { it.videoId }
        return OnlineHomeFeed(shelves, if (shelves.isEmpty()) collectionTracks(root) else tracks, continuation(root, homePage))
    }

    private fun continuation(node: JsonElement, homePage: Boolean = false): String? = when (node) {
        is JsonObject -> {
            val childShelves = setOf("musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation", "musicShelfRenderer", "musicShelfContinuation")
            val body = (listOf("sectionListRenderer", "sectionListContinuation") + if (homePage) emptyList() else childShelves)
                .firstNotNullOfOrNull { node[it] as? JsonObject }
            body?.get("continuations")?.let { OnlineCatalogParser.firstValue(it, "continuation") }
                ?: node["continuationItemRenderer"]?.let { OnlineCatalogParser.firstValue(it, "token") }
                ?: node.filterKeys { it !in setOf("menu", "header") && (!homePage || it !in childShelves) }
                    .values.firstNotNullOfOrNull { continuation(it, homePage) }
        }
        is JsonArray -> node.firstNotNullOfOrNull { continuation(it, homePage) }
        else -> null
    }

    private fun collectionTracks(root: JsonElement): List<OnlineTrack> {
        val tracks = linkedMapOf<String, OnlineTrack>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    if (node.keys.any { it in setOf("musicTwoRowItemRenderer", "musicResponsiveListItemRenderer", "musicMultiRowListItemRenderer", "playlistPanelVideoRenderer") }) {
                        item(node)?.track?.let { tracks.putIfAbsent(it.videoId, it) }
                        return
                    }
                    node.filterKeys { it !in setOf("menu", "header", "frameworkUpdates") }.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return tracks.values.toList()
    }

    internal fun item(node: JsonElement): HomeFeedItem? {
        val wrapper = node as? JsonObject ?: return null
        val entry = listOf("musicTwoRowItemRenderer", "musicResponsiveListItemRenderer", "musicMultiRowListItemRenderer", "playlistPanelVideoRenderer")
            .firstNotNullOfOrNull { key -> (wrapper[key] as? JsonObject)?.let { key to it } } ?: return null
        val (kind, card) = entry
        val columns = card["flexColumns"] as? JsonArray
        val title = OnlineCatalogParser.text(card["title"] ?: columns?.firstOrNull())
        if (title.isBlank()) return null
        val subtitle = OnlineCatalogParser.text(card["subtitle"] ?: columns?.getOrNull(1))
        // Prefer the card's own browse target over overlay "play playlist" video IDs.
        val endpoint = (card["navigationEndpoint"] as? JsonObject)
            ?: ((card["title"] as? JsonObject)?.get("runs") as? JsonArray)?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("navigationEndpoint") as? JsonObject }
            ?: (card["onTap"] as? JsonObject)?.let { (it["innertubeCommand"] as? JsonObject) ?: it }
        val browse = endpoint?.get("browseEndpoint") as? JsonObject
        val browseId = (browse?.get("browseId") as? JsonPrimitive)?.contentOrNull
        val artwork = OnlineCatalogParser.artwork(card["thumbnail"] ?: card)
        if (!browseId.isNullOrBlank()) return HomeFeedItem(title, subtitle, artwork, browseId = browseId,
            params = (browse["params"] as? JsonPrimitive)?.contentOrNull)
        val watchPlaylist = (endpoint?.get("watchPlaylistEndpoint") as? JsonObject)
            ?: (endpoint?.get("watchEndpoint") as? JsonObject)?.takeIf { it["videoId"] == null }
        val playlistId = (watchPlaylist?.get("playlistId") as? JsonPrimitive)?.contentOrNull
        if (!playlistId.isNullOrBlank()) return HomeFeedItem(title, subtitle, artwork, playlistId = playlistId)
        val identity = card["playlistItemData"] ?: card["videoId"]?.let { buildJsonObject { put("videoId", it) } } ?: endpoint ?: card["onTap"]
        val id = (identity?.let { OnlineCatalogParser.firstValue(it, "videoId") })
            ?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return null
        val parsed = OnlineCatalogParser.parse(buildJsonObject { put(if (kind == "musicMultiRowListItemRenderer") "musicTwoRowItemRenderer" else kind, card) })
            .firstOrNull { it.videoId == id }
        val track = parsed ?: OnlineTrack(id, title, subtitle.ifBlank { "YouTube Music" }, artwork)
        return HomeFeedItem(title, track.artist, track.artwork, track = track)
    }
}

internal fun appendCollectionPage(previous: OnlineHomeFeed, page: OnlineHomeFeed): OnlineHomeFeed {
    val shelves = previous.shelves.toMutableList()
    page.shelves.forEach { shelf ->
        val existing = shelves.indexOfFirst { it.title == shelf.title && it.layout == shelf.layout }
        if (existing >= 0) shelves[existing] = shelves[existing].copy(items =
            (shelves[existing].items + shelf.items).distinctBy { it.key })
        else shelves += shelf.copy(id = "${shelves.size}:${shelf.title}")
    }
    return OnlineHomeFeed(shelves, (previous.tracks + page.tracks).distinctBy { it.videoId },
        page.continuation?.takeUnless { it == previous.continuation || (page.shelves.isEmpty() && page.tracks.isEmpty()) })
}
