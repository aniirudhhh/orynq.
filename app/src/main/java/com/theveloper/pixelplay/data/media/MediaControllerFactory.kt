package com.theveloper.pixelplay.data.media

import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaControllerFactory @Inject constructor() {
    fun create(
        context: Context,
        token: SessionToken,
        listener: MediaController.Listener
    ): ListenableFuture<MediaController> {
        val releaseConnection = com.theveloper.pixelplay.data.service.MusicService.beginAppControllerConnection()
        try {
            return MediaController.Builder(context, token)
                .setListener(listener)
                .buildAsync().also { future ->
                    future.addListener(releaseConnection, com.google.common.util.concurrent.MoreExecutors.directExecutor())
                }
        } catch (failure: Throwable) {
            releaseConnection.run()
            throw failure
        }
    }
}
