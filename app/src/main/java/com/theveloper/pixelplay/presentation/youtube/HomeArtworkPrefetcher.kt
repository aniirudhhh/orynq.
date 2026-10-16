package com.theveloper.pixelplay.presentation.youtube

import android.content.Context
import android.net.ConnectivityManager
import coil.ImageLoader
import coil.request.ImageRequest
import com.theveloper.pixelplay.data.youtube.HomeFeedCoordinator
import com.theveloper.pixelplay.data.youtube.highResolutionYouTubeArtwork
import com.theveloper.pixelplay.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeArtworkPrefetcher @Inject constructor(
    @ApplicationContext context: Context,
    imageLoader: ImageLoader,
    home: HomeFeedCoordinator,
    @AppScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            home.state.map { state -> Triple(state.visible, state.namespace,
                (state.quickPicks.ifEmpty { state.tracks }).mapNotNull { it.artwork }.distinct().take(8)) }
                .distinctUntilChanged().collectLatest { (visible, _, urls) ->
                    if (!visible) return@collectLatest
                    val connectivity = context.getSystemService(ConnectivityManager::class.java)
                    if (connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return@collectLatest
                    val density = context.resources.displayMetrics.density
                    // Match the native song-row request dimensions without enlarging either Coil cache.
                    val pixels = (50f * density * density).toInt().coerceIn(1, 1200)
                    val requests = urls.map { url -> imageLoader.enqueue(ImageRequest.Builder(context)
                        .data(highResolutionYouTubeArtwork(url)).size(pixels).allowHardware(false).build()) }
                    try { awaitCancellation() } finally { requests.forEach { it.dispose() } }
                }
        }
    }
}
