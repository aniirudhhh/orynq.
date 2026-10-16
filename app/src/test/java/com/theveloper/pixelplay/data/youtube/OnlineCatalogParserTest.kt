package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OnlineCatalogParserTest {
    @Test fun exactSongSearchDropsUnrelatedRecommendationsWithoutReorderingMatches() {
        val first = OnlineTrack("aaaaaaaaaaa", "ISHQ MUBARAK", "Arijit Singh", null)
        val related = OnlineTrack("bbbbbbbbbbb", "Whistle Of Life", "Ankit Tiwari", null)
        val second = OnlineTrack("ccccccccccc", "Mubarak Ishq", "Another artist", null)
        assertEquals(listOf(first, second), refineMusicSearchResults("Ishq Mubarak", listOf(first, related, second)))
    }
    @Test fun artistSearchAndCombinedSongArtistQueryUseMusicMetadata() {
        val track = OnlineTrack("aaaaaaaaaaa", "Get Lucky", "Daft Punk", null)
        val unrelated = OnlineTrack("bbbbbbbbbbb", "Lucky", "Another artist", null)
        assertEquals(listOf(track), refineMusicSearchResults("Daft Punk", listOf(track, unrelated)))
        assertEquals(listOf(track), refineMusicSearchResults("Get Lucky Daft Punk", listOf(track, unrelated)))
    }
    @Test fun unmatchedTyposAndDifferentScriptsKeepServerResults() {
        val tracks = listOf(OnlineTrack("aaaaaaaaaaa", "कितना हसीन चेहरा", "Kumar Sanu", null))
        assertEquals(tracks, refineMusicSearchResults("kitna haseen chehra", tracks))
    }
    @Test fun songSearchExcludesVideoCardsAndKeepsMusicRanking() {
        val root = Json.parseToJsonElement("""[
          {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"aaaaaaaaaaa"},"flexColumns":[{"text":{"runs":[{"text":"First ranked song"}]}}],"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}},
          {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"bbbbbbbbbbb"},"flexColumns":[{"text":{"runs":[{"text":"Video upload"}]}}],"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}},
          {"videoRenderer":{"videoId":"ccccccccccc","title":{"simpleText":"Related video"}}},
          {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"ddddddddddd"},"flexColumns":[{"text":{"runs":[{"text":"Second ranked song"}]}}],"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}
        ]""")
        assertEquals(listOf("aaaaaaaaaaa", "ddddddddddd"), OnlineCatalogParser.parse(root, songsOnly = true).map { it.videoId })
    }
    @Test fun songIdentityNeverComesFromAnUnrelatedMenuAction() {
        val root = Json.parseToJsonElement("""{"musicResponsiveListItemRenderer":{
          "menu":{"watchEndpoint":{"videoId":"bbbbbbbbbbb"}},
          "playlistItemData":{"videoId":"aaaaaaaaaaa"},
          "flexColumns":[{"text":{"runs":[{"text":"Song"}]}}]
        }}""")
        assertEquals("aaaaaaaaaaa", OnlineCatalogParser.parse(root, songsOnly = true).single().videoId)
    }
    @Test fun upgradesKnownArtworkWithoutChangingUnrelatedUrls() {
        assertEquals("https://yt3.googleusercontent.com/art=w1200-h1200-l90-rj", highResolutionYouTubeArtwork("https://yt3.googleusercontent.com/art=w60-h60-l90-rj"))
        assertEquals("https://lh3.googleusercontent.com/art=w1200-h1200-l90-rj", highResolutionYouTubeArtwork("https://lh3.googleusercontent.com/art=w60-h60-l90-rj"))
        assertEquals("https://yt3.ggpht.com/art=s1200-c", highResolutionYouTubeArtwork("https://yt3.ggpht.com/art=s60-c"))
        assertEquals("https://example.com/art=w60-h60", highResolutionYouTubeArtwork("https://example.com/art=w60-h60"))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg?sqp=small&rs=signature", highResolutionYouTubeArtwork("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg?sqp=small&rs=signature"))
        assertEquals("https://i.ytimg.com/other/image.jpg", highResolutionYouTubeArtwork("https://i.ytimg.com/other/image.jpg"))
    }
    @Test fun prefersArtistNavigationOverCardTypeLabels() {
        val track = OnlineCatalogParser.parse(Json.parseToJsonElement("""{
          "musicResponsiveListItemRenderer": {
            "playlistItemData":{"videoId":"abcdefghijk"},
            "flexColumns":[
              {"text":{"runs":[{"text":"Get Lucky"}]}},
              {"text":{"runs":[{"text":"Song • 6:10"}]}},
              {"text":{"runs":[{"text":"Daft Punk","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}]}}
            ]
          }
        }""" )).single()
        assertEquals("Daft Punk", track.artist)
    }
    @Test fun choosesLargeArtworkAndParsesDuration() {
        val result = OnlineCatalogParser.parse(Json.parseToJsonElement("""{
          "musicTwoRowItemRenderer": {
            "navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},
            "title":{"runs":[{"text":"Song"}]},
            "subtitle":{"runs":[{"text":"Artist"}]},
            "lengthText":{"simpleText":"1:02:03"},
            "thumbnail":{"thumbnails":[
              {"url":"https://example.com/small.jpg","width":60},
              {"url":"https://example.com/large.jpg","width":600}
            ]}
          }
        }"""))
        assertEquals("https://example.com/large.jpg", result.single().artwork)
        assertEquals(3_723_000L, result.single().durationMs)
    }
    @Test fun parsesMusicCardsAndDeduplicatesVideoIds() {
        val card = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"A song"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"An artist"}]}}}],"thumbnail":{"thumbnails":[{"url":"https://example.com/art.jpg"}]}}}"""
        val tracks = OnlineCatalogParser.parse(Json.parseToJsonElement("[$card,$card]"))
        assertEquals(1, tracks.size)
        assertEquals("A song", tracks.single().title)
        assertEquals("An artist", tracks.single().artist)
        assertEquals("https://example.com/art.jpg", tracks.single().artwork)
    }
    @Test fun ignoresNonPlayableAlbumsAndMalformedIds() {
        val tracks = OnlineCatalogParser.parse(Json.parseToJsonElement("""[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Album"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}}},
            {"videoRenderer":{"videoId":"invalid","title":{"simpleText":"Broken"}}}
        ]"""))
        assertTrue(tracks.isEmpty())
    }
    @Test fun createsStableQueueIdentityWithoutPersistingStreamUrls() {
        val song = OnlineTrack("abcdefghijk", "Song", "Artist", null).asSong()
        assertEquals("youtube:abcdefghijk", song.id)
        assertEquals("youtube://abcdefghijk", song.contentUriString)
        assertEquals("", song.path)
    }
}
