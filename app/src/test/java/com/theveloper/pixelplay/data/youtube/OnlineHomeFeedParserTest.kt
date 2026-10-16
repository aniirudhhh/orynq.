package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OnlineHomeFeedParserTest {
    private fun song(id: String, title: String = id) = """{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"$id"},"flexColumns":[{"text":{"runs":[{"text":"$title"}]}},
        {"text":{"runs":[{"text":"Artist"}]}}]}}"""
    private fun shelf(title: String, cards: String) = """{"musicCarouselShelfRenderer":{
        "header":{"musicCarouselShelfBasicHeaderRenderer":{"strapline":{"runs":[{"text":"Account"}]},
        "title":{"runs":[{"text":"$title"}]}}},"contents":[$cards]}}"""
    @Test fun preservesServerSectionAndSongOrderWithActualHeaderTitle() {
        val feed = OnlineHomeFeedParser.parse(Json.parseToJsonElement("""{"contents":[
            ${shelf("Quick picks", song("bbbbbbbbbbb") + "," + song("aaaaaaaaaaa"))},
            ${shelf("Forgotten favourites", song("ccccccccccc"))}]}"""))
        assertEquals(listOf("Quick picks", "Forgotten favourites"), feed.shelves.map { it.title })
        assertEquals(HomeShelfLayout.ROWS, feed.shelves.first().layout)
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa", "ccccccccccc"), feed.tracks.map { it.videoId })
    }
    @Test fun collectionNavigationWinsOverOverlayVideoAndMenuActions() {
        val card = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"My mix"}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"VLmix","params":"abc"}},
            "overlay":{"watchEndpoint":{"videoId":"aaaaaaaaaaa"}},
            "menu":${song("bbbbbbbbbbb")}}}"""
        val feed = OnlineHomeFeedParser.parse(Json.parseToJsonElement(shelf("Mixes", card)))
        val item = feed.shelves.single().items.single()
        assertEquals("VLmix", item.browseId)
        assertEquals("abc", item.params)
        assertNull(item.track)
        assertTrue(feed.tracks.isEmpty())
        assertEquals(HomeShelfLayout.CARDS, feed.shelves.single().layout)
    }
    @Test fun deduplicatesWithinSectionButRetainsSongInDifferentSections() {
        val card = song("aaaaaaaaaaa")
        val feed = OnlineHomeFeedParser.parse(Json.parseToJsonElement("[${shelf("One", "$card,$card")},${shelf("Two",card)}]"))
        assertEquals(listOf(1,1), feed.shelves.map { it.items.size })
        assertEquals(1, feed.tracks.size)
    }
    @Test fun malformedUnknownAndEmptyCardsDoNotBecomeDeadButtons() {
        val cards = """{"musicTwoRowItemRenderer":{"title":{"simpleText":"Missing target"}}},
            {"unknownRenderer":{"title":"Unknown"}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"invalid"}}}"""
        assertTrue(OnlineHomeFeedParser.parse(Json.parseToJsonElement(shelf("Bad", cards))).shelves.isEmpty())
    }
    @Test fun gridAndMultiRowCardsUseCompactGridLayout() {
        val root = """{"musicGridRenderer":{"title":{"simpleText":"Speed dial"},"items":[
            {"musicMultiRowListItemRenderer":{"title":{"simpleText":"Pinned song"},
            "onTap":{"watchEndpoint":{"videoId":"aaaaaaaaaaa"}}}}]}}"""
        val shelf = OnlineHomeFeedParser.parse(Json.parseToJsonElement(root)).shelves.single()
        assertEquals(HomeShelfLayout.GRID, shelf.layout)
        assertEquals("aaaaaaaaaaa", shelf.items.single().track?.videoId)
    }
    @Test fun unheadedPlaylistTrackListRemainsPlayableAndCacheRoundTrips() {
        val root = """{"musicPlaylistShelfRenderer":{"contents":[${song("aaaaaaaaaaa")},${song("bbbbbbbbbbb")}]}}"""
        val feed = OnlineHomeFeedParser.parse(Json.parseToJsonElement(root))
        assertEquals(2, feed.tracks.size)
        assertEquals(feed, Json.decodeFromString<OnlineHomeFeed>(Json.encodeToString(feed)))
    }
    @Test fun mixPlaybackTargetRemainsACollectionRatherThanAnArbitrarySong() {
        val card = """{"musicTwoRowItemRenderer":{"title":{"simpleText":"My Supermix"},
            "navigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"RDmix"}},
            "menu":{"watchEndpoint":{"videoId":"aaaaaaaaaaa"}}}}"""
        val entry = OnlineHomeFeedParser.parse(Json.parseToJsonElement(shelf("Made for you",card))).shelves.single().items.single()
        assertEquals("RDmix", entry.playlistId)
        assertNull(entry.track)
    }
    @Test fun playlistContinuationPreservesExistingSongsAndStopsRepeatedTokens() {
        val first = OnlineHomeFeedParser.parse(Json.parseToJsonElement("""{"musicPlaylistShelfRenderer":{
            "contents":[${song("aaaaaaaaaaa")}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}"""))
        assertEquals("next", first.continuation)
        val next = first.copy(tracks = listOf(OnlineTrack("bbbbbbbbbbb","Next","Artist",null)))
        val merged = appendCollectionPage(first, next)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), merged.tracks.map { it.videoId })
        assertNull(merged.continuation)
    }
    @Test fun playlistPlaybackPanelResolvesDirectVideoId() {
        val feed = OnlineHomeFeedParser.parse(Json.parseToJsonElement("""{"playlistPanelRenderer":{"contents":[
            {"playlistPanelVideoRenderer":{"videoId":"aaaaaaaaaaa","title":{"simpleText":"Mix song"},
            "longBylineText":{"simpleText":"Artist"}}}]}}"""))
        assertEquals("aaaaaaaaaaa",feed.tracks.single().videoId)
    }
    @Test fun collectionSongsWinOverRecommendationShelvesAndKeepTheirOrder() {
        val root = Json.parseToJsonElement("""{"contents":[
            ${shelf("You might also like", song("ccccccccccc"))},
            {"musicPlaylistShelfRenderer":{"contents":[${song("bbbbbbbbbbb")},${song("aaaaaaaaaaa")}],
            "continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}""")
        val feed = OnlineHomeFeedParser.parseCollection(root)
        assertTrue(feed.shelves.isEmpty())
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), feed.tracks.map { it.videoId })
        assertEquals("next",feed.continuation)
    }
    @Test fun multiRowCollectionTapIsPreserved() {
        val card = """{"musicMultiRowListItemRenderer":{"title":{"simpleText":"Pinned playlist"},
            "onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"VLpinned"}}}}}"""
        val entry = OnlineHomeFeedParser.parse(Json.parseToJsonElement(shelf("Speed dial",card))).shelves.single().items.single()
        assertEquals("VLpinned",entry.browseId)
        assertNull(entry.track)
    }
    @Test fun homePaginationUsesSectionTokenRatherThanChildShelfToken() {
        val root = Json.parseToJsonElement("""{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"title":{"simpleText":"Songs"},"contents":[${song("aaaaaaaaaaa")}],
            "continuations":[{"nextContinuationData":{"continuation":"songs-page"}}]}}],
            "continuations":[{"nextContinuationData":{"continuation":"home-page"}}]}}""")
        assertEquals("home-page",OnlineHomeFeedParser.parse(root, homePage = true).continuation)
    }
    @Test fun homeDoesNotFollowAnIndividualSongsShelfContinuation() {
        val root = Json.parseToJsonElement("""{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"title":{"simpleText":"Songs"},"contents":[${song("aaaaaaaaaaa")}],
            "continuations":[{"nextContinuationData":{"continuation":"songs-page"}}]}}]}}""")
        assertNull(OnlineHomeFeedParser.parse(root, homePage = true).continuation)
    }
    @Test fun continuationCommandAndNewShelfIdsArePreserved() {
        val first = OnlineHomeFeedParser.parse(Json.parseToJsonElement(shelf("First",song("aaaaaaaaaaa"))))
        val page = OnlineHomeFeedParser.parse(Json.parseToJsonElement("""{"appendContinuationItemsAction":{"continuationItems":[
            ${shelf("Next",song("bbbbbbbbbbb"))},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"more"}}}}]}}"""))
        val merged = appendCollectionPage(first,page)
        assertEquals(listOf("First","Next"),merged.shelves.map { it.title })
        assertEquals(2,merged.shelves.map { it.id }.distinct().size)
        assertEquals("more",merged.continuation)
    }
}
