package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryUrlTest {
    @Test fun acceptsOnlyHttpsPlaybackTrackingOnKnownHosts() {
        assertTrue(allowedPlaybackHistoryUrl("https://s.youtube.com/api/stats/playback?token=example"))
        for (url in listOf("http://s.youtube.com/api/stats/playback?t=x",
            "https://s.youtube.com.evil.test/api/stats/playback?t=x", "https://evil.test/api/stats/playback?t=x",
            "https://s.youtube.com/api/stats/watchtime?t=x", "https://u@s.youtube.com/api/stats/playback?t=x",
            "https://s.youtube.com:8080/api/stats/playback?t=x", "https://s.youtube.com/api/stats/playback?t=x#fragment")) {
            assertFalse(url, allowedPlaybackHistoryUrl(url))
        }
    }
    @Test fun trackingMustBelongToTheActualVideoAndExpectedField() {
        val root = Json.parseToJsonElement("""{"videoDetails":{"videoId":"abcdefghijk"},
            "playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?x=1"}}}""")
        assertNotNull(playbackHistoryUrl(root, "abcdefghijk"))
        assertNull(playbackHistoryUrl(root, "zzzzzzzzzzz"))
        assertNull(playbackHistoryUrl(Json.parseToJsonElement("{}"), "abcdefghijk"))
    }
}
