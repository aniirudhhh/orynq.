package com.theveloper.pixelplay.data.youtube

import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class YouTubeNetworkFailureTest {
    @Test fun transportFailuresAreTaggedButPermanentErrorsAreNot() {
        assertTrue(youtubeNetworkFailure(UnknownHostException()))
        assertTrue(youtubeNetworkFailure(SocketTimeoutException()))
        assertTrue(youtubeNetworkFailure(IOException("open failed", UnknownHostException())))
        assertFalse(youtubeNetworkFailure(IOException("Unsupported stream length")))
        assertFalse(youtubeNetworkFailure(IllegalArgumentException("bad format")))
    }
    @Test fun publicFailurePreservesOnlyCategoryAndSafeMessage() {
        val error = YouTubeAudioIOException(true, "Audio was interrupted")
        assertTrue(error.networkFailure)
        assertNull(error.cause)
        assertEquals("Audio was interrupted", error.message)
    }
}
