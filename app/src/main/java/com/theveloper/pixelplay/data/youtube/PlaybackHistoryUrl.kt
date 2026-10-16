package com.theveloper.pixelplay.data.youtube

import java.net.URI
import kotlinx.serialization.json.*

internal fun playbackHistoryUrl(root: JsonElement, videoId: String): String? {
    val body = root as? JsonObject ?: return null
    val actual = (body["videoDetails"] as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull
    if (actual != videoId) return null
    val url = ((body["playbackTracking"] as? JsonObject)?.get("videostatsPlaybackUrl") as? JsonObject)
        ?.get("baseUrl")?.jsonPrimitive?.contentOrNull ?: return null
    return url.takeIf(::allowedPlaybackHistoryUrl)
}
internal fun allowedPlaybackHistoryUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    url.length <= 16_384 && uri.scheme == "https" && uri.host in setOf(
        "s.youtube.com", "www.youtube.com", "music.youtube.com", "youtube.com") &&
        uri.rawUserInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null &&
        uri.path == "/api/stats/playback" && !uri.rawQuery.isNullOrBlank()
}.getOrDefault(false)
