package com.theveloper.pixelplay.data.service

import org.junit.Assert.*
import org.junit.Test

class PlaybackForegroundPolicyTest {
    @Test fun openingAppDoesNotPromoteBindOnlyService() {
        assertFalse(shouldPromotePlaybackServiceOnCreate(37,false,true))
        assertFalse(shouldPromotePlaybackServiceOnCreate(26,false,true))
    }
    @Test fun coldExternalStartsPreserveForegroundDeadlineProtection() {
        assertTrue(shouldPromotePlaybackServiceOnCreate(37,true,true))
        assertTrue(shouldPromotePlaybackServiceOnCreate(37,false,false))
        assertFalse(shouldPromotePlaybackServiceOnCreate(25,false,false))
    }
    @Test fun activePlaybackNotificationIsNotReplacedByPlaceholder() {
        assertFalse(shouldShowTemporaryPlaybackNotification(true,true,true,true,false,true))
    }
    @Test fun idleExternalCommandsStillPromoteAndIdleBindingDoesNot() {
        assertTrue(shouldShowTemporaryPlaybackNotification(false,true,false,false,false,false))
        assertTrue(shouldShowTemporaryPlaybackNotification(false,false,true,false,false,false))
        assertTrue(shouldShowTemporaryPlaybackNotification(false,false,false,true,false,false))
        assertTrue(shouldShowTemporaryPlaybackNotification(false,false,false,false,false,true))
        assertFalse(shouldShowTemporaryPlaybackNotification(false,false,false,false,false,false))
    }
    @Test fun mediaButtonDoesNotDoublePromoteAfterCreate() {
        assertFalse(shouldShowTemporaryPlaybackNotification(false,false,false,true,true,false))
    }
}
