package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class YouTubeLyricsParserTest {
    @Test fun lyricsTabIsSelectedInsteadOfRelatedOrAlbum() {
        val root = Json.parseToJsonElement("""{"contents":{"watchNextTabbedResultsRenderer":{"tabs":[
          {"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}}},
          {"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"MPLYtlyrics"}}}}
        ]}}}""")
        assertEquals("MPLYtlyrics", YouTubeLyricsParser.browseId(root))
        assertNull(YouTubeLyricsParser.browseId(Json.parseToJsonElement("""{"browseEndpoint":{"browseId":"MPLYtoutside"}}""")))
    }
    @Test fun joinsLyricsRunsWithoutIncludingAttributionOrOtherText() {
        val root = Json.parseToJsonElement("""{"contents":{"sectionListRenderer":{"contents":[
          {"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"First\n"},{"text":"Second"}]},"footer":{"runs":[{"text":"Source"}]}}}
        ]}}}""")
        assertEquals("First\nSecond", YouTubeLyricsParser.lyrics(root))
        assertNull(YouTubeLyricsParser.lyrics(Json.parseToJsonElement("""{"description":{"simpleText":"Not lyrics"}}""")))
        assertNull(YouTubeLyricsParser.lyrics(Json.parseToJsonElement("""{"musicDescriptionShelfRenderer":{"description":{"runs":[]}}}""")))
    }
}
