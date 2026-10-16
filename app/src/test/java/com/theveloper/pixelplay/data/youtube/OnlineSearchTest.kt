package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.model.SearchFilterType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OnlineSearchTest {
    private fun card(title: String, id: String, video: Boolean = false): JsonObject {
        val text = buildJsonObject {
            put("runs", buildJsonArray { add(buildJsonObject { put("text", title) }) })
        }
        val column = buildJsonObject {
            put("musicResponsiveListItemFlexColumnRenderer", buildJsonObject { put("text", text) })
        }
        val body = buildJsonObject {
            put("flexColumns", buildJsonArray { add(column) })
            put("navigationEndpoint", buildJsonObject {
                put(if (video) "watchEndpoint" else "browseEndpoint", buildJsonObject {
                    put(if (video) "videoId" else "browseId", id)
                })
            })
            if (video) put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
            put("menu", buildJsonObject { put("browseEndpoint", buildJsonObject { put("browseId", "VLmenu") }) })
        }
        return buildJsonObject { put("musicResponsiveListItemRenderer", body) }
    }
    private fun response(vararg cards: JsonObject) = buildJsonObject {
        put("contents", buildJsonObject { put("musicShelfRenderer", buildJsonObject {
            put("contents", JsonArray(cards.toList()))
        }) })
    }
    @Test fun allIncludesTypedCollectionsAndPlayableSongs() {
        val result = parseMusicSearch(response(card("Song", "abcdefghijk", true), card("Album", "MPREalbum"),
            card("Artist", "UCartist"), card("Playlist", "VLplaylist")), SearchFilterType.ALL)
        assertEquals(listOf("abcdefghijk"), result.tracks.map { it.videoId })
        assertEquals(listOf(SearchFilterType.ALBUMS, SearchFilterType.ARTISTS, SearchFilterType.PLAYLISTS), result.collections.map { it.type })
        assertEquals(listOf("MPREalbum", "UCartist", "VLplaylist"), result.collections.map { it.item.browseId })
    }
    @Test fun eachFilterExcludesOtherTypesEvenWhenServerPadsResults() {
        val root = response(card("Song", "abcdefghijk", true), card("Album", "MPREalbum"),
            card("Artist", "UCartist"), card("Playlist", "VLplaylist"), card("Podcast", "MPSPpodcast"))
        for (filter in listOf(SearchFilterType.ALBUMS, SearchFilterType.ARTISTS, SearchFilterType.PLAYLISTS)) {
            val result = parseMusicSearch(root, filter)
            assertTrue(result.tracks.isEmpty())
            assertEquals(listOf(filter), result.collections.map { it.type })
        }
        assertTrue(parseMusicSearch(root, SearchFilterType.SONGS).collections.isEmpty())
    }
    @Test fun filtersUseDistinctServerParamsAndAllHasNoRestriction() {
        assertNull(musicSearchParams(SearchFilterType.ALL))
        val params = SearchFilterType.entries.filter { it != SearchFilterType.ALL }.map(::musicSearchParams)
        assertEquals(4, params.distinct().size)
        assertTrue(params.all { !it.isNullOrBlank() })
    }
    @Test fun duplicatesMenusAndUnsupportedItemsDoNotBecomeResults() {
        val album = card("Album", "MPREalbum")
        val result = parseMusicSearch(response(album, album, card("Podcast", "MPSPpodcast")), SearchFilterType.ALL)
        assertEquals(1, result.collections.size)
        assertTrue(result.tracks.isEmpty())
        assertTrue(parseMusicSearch(buildJsonObject { put("menu", album) }, SearchFilterType.ALL).collections.isEmpty())
        assertTrue(parseMusicSearch(Json.parseToJsonElement("{}"), SearchFilterType.ALL).tracks.isEmpty())
    }
}
