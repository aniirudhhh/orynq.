package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class OnlineSongMetadataTest {
    @Test fun readsFixedColumnDurationAndActualAlbum() {
        val root = Json.parseToJsonElement("""{"musicResponsiveListItemRenderer":{
          "playlistItemData":{"videoId":"aaaaaaaaaaa"},
          "flexColumns":[{"text":{"runs":[{"text":"Song"}]}},{"text":{"runs":[
            {"text":"Actual album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE_album"}}}]}}],
          "fixedColumns":[{"text":{"runs":[{"text":"4:37"}]}}]}}""")
        val track = OnlineCatalogParser.parse(root).single()
        assertEquals(277000L, track.durationMs)
        assertEquals("Actual album", track.asSong().album)
    }
    @Test fun onlyUsesRequestedVideoDuration() {
        val root = Json.parseToJsonElement("""{"videoDetails":{"videoId":"bbbbbbbbbbb","lengthSeconds":"999"},
          "items":[{"playlistPanelVideoRenderer":{"videoId":"aaaaaaaaaaa","lengthText":{"simpleText":"3:21"}}},
          {"playlistPanelVideoRenderer":{"videoId":"ccccccccccc","lengthText":{"simpleText":"9:59"}}}]}""")
        assertEquals(201000L, parseOnlineSongMetadata(root,"aaaaaaaaaaa").durationMs)
    }
    @Test fun playerDetailsSupplyDurationWithoutPretendingProviderIsAlbum() {
        val info = parseOnlineSongMetadata(Json.parseToJsonElement("""{"videoDetails":{"videoId":"aaaaaaaaaaa","lengthSeconds":"243","author":"Artist"}}"""),"aaaaaaaaaaa")
        assertEquals(243000L, info.durationMs)
        assertNull(info.album)
    }
    @Test fun watchMetadataAlbumExcludesRecommendationAlbums() {
        val root = Json.parseToJsonElement("""{"recommendations":{"text":"Wrong album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE_wrong"}}},
          "musicWatchMetadataRenderer":{"subtitle":{"runs":[{"text":"Right album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE_right"}}}]}}}""")
        assertEquals("Right album",parseOnlineSongMetadata(root,"aaaaaaaaaaa").album)
    }
    @Test fun supportsHoursAndRejectsMissingOrInvalidDuration() {
        assertEquals(3723000L,musicDurationMs("1:02:03"))
        assertNull(musicDurationMs("4:75"))
        assertNull(musicDurationMs("0:00"))
        assertNull(musicDurationMs("3.2M plays"))
    }
    @Test fun legacyCachedTracksStillDecodeAndMissingDetailsStayAbsent() {
        val track = Json.decodeFromString<OnlineTrack>("""{"videoId":"aaaaaaaaaaa","title":"Song","artist":"Artist","artwork":null}""")
        assertNull(track.album)
        assertEquals(OnlineSongMetadata(), parseOnlineSongMetadata(Json.parseToJsonElement("{}"),track.videoId))
        assertEquals(OnlineSongMetadata(1000,"Album"),OnlineSongMetadata(1000).merge(OnlineSongMetadata(album="Album")))
    }
}
