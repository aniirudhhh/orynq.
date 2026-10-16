package com.theveloper.pixelplay.data.youtube

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.models.YouTubeClient

/** Preserve capability/token checks, then prefer usable Music clients over general clients. */
internal class MusicFirstClientStrategy : ClientFallbackStrategy {
    private val delegate = ContentAwareFallbackStrategy()
    private fun rank(name: String) = when (name) {
        "WEB_REMIX", "ANDROID_MUSIC" -> 0
        else -> 1
    }
    override fun resolveClients(hints: ContentHints): List<YouTubeClient> =
        delegate.resolveClients(hints).sortedBy { rank(it.clientName) }

    override fun selectClients(request: ClientSelectionRequest): ClientSelectionResult {
        val selection = delegate.selectClients(request)
        return selection.copy(candidates = selection.candidates.sortedBy { rank(it.client.clientName) })
    }
}
