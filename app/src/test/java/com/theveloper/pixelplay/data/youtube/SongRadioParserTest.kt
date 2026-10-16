package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SongRadioParserTest {
    @Test fun radioUsesOnlyQueuePanelExcludesSeedAndDeduplicatesTracks() {
        val root = Json.parseToJsonElement("""{
          "queue":{"playlistPanelRenderer":{"contents":[
            {"playlistPanelVideoRenderer":{"videoId":"aaaaaaaaaaa","title":{"simpleText":"Seed"}}},
            {"playlistPanelVideoRenderer":{"videoId":"bbbbbbbbbbb","title":{"simpleText":"Related song"},
              "longBylineText":{"runs":[{"text":"Related artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UC_artist"}}}]},
              "lengthText":{"simpleText":"3:45"},"menu":{"videoId":"ccccccccccc"}}},
            {"playlistPanelVideoRenderer":{"videoId":"bbbbbbbbbbb","title":{"simpleText":"Duplicate"}}}
          ]}},
          "other":{"musicTwoRowItemRenderer":{"title":{"simpleText":"Unrelated card"},"navigationEndpoint":{"watchEndpoint":{"videoId":"ddddddddddd"}}}}
        }""")
        val tracks = parseSongRadio(root, "aaaaaaaaaaa")
        assertEquals(listOf("bbbbbbbbbbb"), tracks.map { it.videoId })
        assertEquals("Related artist", tracks.single().artist)
        assertEquals(225_000L, tracks.single().durationMs)
    }

    @Test fun missingRadioPanelDoesNotFallbackToHomeCards() {
        assertTrue(parseSongRadio(Json.parseToJsonElement("""{"musicTwoRowItemRenderer":{"title":{"simpleText":"Home"},"videoId":"bbbbbbbbbbb"}}"""), "aaaaaaaaaaa").isEmpty())
    }
}
