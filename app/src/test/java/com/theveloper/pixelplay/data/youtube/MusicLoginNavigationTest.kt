package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.presentation.youtube.isAllowedMusicLoginUrl
import org.junit.Assert.*
import org.junit.Test

class MusicLoginNavigationTest {
    @Test fun allowsOnlyTrustedHttpsLoginOrigins() {
        for (url in listOf("https://music.youtube.com/", "https://accounts.google.com/signin/v2/identifier",
            "https://consent.google.com/", "https://accounts.youtube.com/accounts/SetSID")) {
            assertTrue(isAllowedMusicLoginUrl(url))
        }
    }
    @Test fun allowsGoogleCookieHandoffWithoutOpeningGeneralGoogleNavigation() {
        assertTrue(isAllowedMusicLoginUrl("https://www.google.com/accounts/SetSID?opaque=synthetic"))
        assertTrue(isAllowedMusicLoginUrl("https://www.google.co.in/accounts/CheckCookie"))
        assertFalse(isAllowedMusicLoginUrl("https://www.google.com/search?q=example"))
        assertFalse(isAllowedMusicLoginUrl("https://www.google.com/accounts-evil/SetSID"))
        assertFalse(isAllowedMusicLoginUrl("https://www.google.com.evil.example/accounts/SetSID"))
    }
    @Test fun allowsIndianAccountRedirectAndRejectsImpersonatingDomains() {
        assertTrue(isAllowedMusicLoginUrl("https://accounts.google.co.in/signin/v2/challenge"))
        assertTrue(isAllowedMusicLoginUrl("https://accounts.google.co.in/accounts/SetSID"))
        assertFalse(isAllowedMusicLoginUrl("https://accounts.google.co.in.attacker.example/signin"))
        assertFalse(isAllowedMusicLoginUrl("https://accounts-google.co.in/signin"))
        assertFalse(isAllowedMusicLoginUrl("http://accounts.google.co.in/signin"))
    }
    @Test fun rejectsLookalikesLocalFilesExternalAppsAndUnexpectedPorts() {
        for (url in listOf("http://music.youtube.com/", "https://accounts.google.com.attacker.example/",
            "https://accounts.google.com@attacker.example/", "https://attacker@accounts.google.com/",
            "https://music.youtube.com:8443/", "file:///data/private", "intent://music.youtube.com/", "javascript:alert(1)")) {
            assertFalse(isAllowedMusicLoginUrl(url))
        }
    }
}
