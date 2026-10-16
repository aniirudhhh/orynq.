package com.theveloper.pixelplay.presentation.components

import android.content.Context
import android.net.Uri
import coil.request.ImageRequest
import com.theveloper.pixelplay.data.youtube.highResolutionYouTubeArtwork

/** Also handles tracks retained in an old media session, before catalog conversion. */
internal fun normalizeYouTubeArtworkModel(model: Any?, context: Context): Any? = when (model) {
    is String -> highResolutionYouTubeArtwork(model)
    is Uri -> Uri.parse(highResolutionYouTubeArtwork(model.toString()))
    is ImageRequest -> {
        val original = model.data
        val upgraded = normalizeYouTubeArtworkModel(original, context)
        if (original == upgraded) model else model.newBuilder(context).data(upgraded)
            .memoryCacheKey(null as String?).diskCacheKey(null).build()
    }
    else -> model
}
