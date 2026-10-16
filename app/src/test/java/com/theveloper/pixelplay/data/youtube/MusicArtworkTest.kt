package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MusicArtworkTest {
    private val cover = "https://yt3.googleusercontent.com/album=w120-h120-l90-rj"
    private val video = OnlineTrack("aaaaaaaaaaa", "Ishq Mubarak (Official Video)", "Arijit Singh", "https://i.ytimg.com/vi/aaaaaaaaaaa/maxresdefault.jpg", 300_000)
    @Test fun matchesOfficialAlbumWithoutReplacingPlaybackIdentity() {
        val song = OnlineTrack("bbbbbbbbbbb", "ISHQ MUBARAK", "Arijit Singh, Ankit Tiwari", cover, 301_000)
        assertEquals(highResolutionYouTubeArtwork(cover), matchingMusicArtwork(video, listOf(song)))
    }
    @Test fun rejectsWrongArtistVersionAndDuration() {
        val song = OnlineTrack("bbbbbbbbbbb", "Ishq Mubarak", "Arijit Singh", cover, 300_000)
        assertNull(matchingMusicArtwork(video, listOf(song.copy(artist = "Another Singer"))))
        assertNull(matchingMusicArtwork(video, listOf(song.copy(title = "Ishq Mubarak Remix"))))
        assertNull(matchingMusicArtwork(video, listOf(song.copy(durationMs = 600_000))))
    }
    @Test fun prefersSquareMusicCoverOverLargerVideoFrame() {
        val root = Json.parseToJsonElement("""{"musicTwoRowItemRenderer":{"navigationEndpoint":{"watchEndpoint":{"videoId":"aaaaaaaaaaa"}},"title":{"simpleText":"Song"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/aaaaaaaaaaa/maxresdefault.jpg","width":1280,"height":720},{"url":"https://yt3.googleusercontent.com/album=w120-h120-l90-rj","width":120,"height":120}]}}}""")
        assertEquals(cover, OnlineCatalogParser.parse(root).single().artwork)
    }
    @Test fun preservesMusicCropAndUpgradesPlainVideoFallback() {
        val cropped = "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg?sqp=crop&rs=signature"
        assertEquals(cropped, highResolutionYouTubeArtwork(cropped))
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/maxresdefault.jpg", highResolutionYouTubeArtwork("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg"))
    }
}
