package com.theveloper.pixelplay.data.service

internal fun shouldPromotePlaybackServiceOnCreate(
    sdkInt: Int,
    hasPendingExternalCommand: Boolean,
    isConnectingFromApp: Boolean
): Boolean = hasPendingExternalCommand || (sdkInt >= 26 && !isConnectingFromApp)

internal fun shouldShowTemporaryPlaybackNotification(
    alreadyForeground: Boolean,
    forcedForegroundStart: Boolean,
    pendingExternalCommand: Boolean,
    mediaButtonIntent: Boolean,
    startedDuringCreate: Boolean,
    playbackAction: Boolean
): Boolean = !alreadyForeground && (forcedForegroundStart || pendingExternalCommand ||
    (mediaButtonIntent && !startedDuringCreate) || playbackAction)
