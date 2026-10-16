package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.*

/** Parse only the lyrics shelf; never mistake video descriptions or related cards for lyrics. */
internal object YouTubeLyricsParser {
    private fun find(node: JsonElement, key: String): JsonObject? = when (node) {
        is JsonObject -> node[key] as? JsonObject ?: node.values.firstNotNullOfOrNull { find(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { find(it, key) }
        else -> null
    }
    fun browseId(root: JsonElement): String? {
        val watch = find(root, "watchNextTabbedResultsRenderer") ?: return null
        fun id(node: JsonElement): String? = when (node) {
            is JsonObject -> {
                val endpoint = node["browseEndpoint"] as? JsonObject
                val value = (endpoint?.get("browseId") as? JsonPrimitive)?.contentOrNull
                value?.takeIf { it.startsWith("MPLYt") }
                    ?: node.values.firstNotNullOfOrNull { id(it) }
            }
            is JsonArray -> node.firstNotNullOfOrNull { id(it) }
            else -> null
        }
        return id(watch)
    }
    fun lyrics(root: JsonElement): String? {
        val shelf = find(root, "musicDescriptionShelfRenderer") ?: return null
        val description = shelf["description"] as? JsonObject ?: return null
        val raw = (description["runs"] as? JsonArray)?.joinToString("") {
            ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        } ?: (description["simpleText"] as? JsonPrimitive)?.contentOrNull
        return raw?.trim()?.takeIf { it.isNotBlank() && it.length <= 200_000 }
    }
}
