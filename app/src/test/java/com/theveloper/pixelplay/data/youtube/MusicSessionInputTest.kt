package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MusicSessionInputTest {
    @Test fun parsesExplicitHeaderAndBrowserJson() {
        assertEquals("SAPISID=synthetic; SID=example", parseMusicSessionCookie("Cookie: SAPISID=synthetic; SID=example"))
        assertEquals("__Secure-3PAPISID=synthetic", parseMusicSessionCookie("""{"Cookie":"__Secure-3PAPISID=synthetic","Authorization":"ignored"}"""))
    }
    @Test fun netscapeImportRejectsOtherDomainsExpiredAndInsecureCookies() {
        val input = listOf(
            "# Netscape HTTP Cookie File",
            "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\tsynthetic",
            ".google.com\tTRUE\t/\tTRUE\t0\tSID\tprivate-other-domain",
            ".youtube.com\tTRUE\t/\tTRUE\t1\tSID\texpired",
            ".youtube.com\tTRUE\t/\tFALSE\t0\tSID\tinsecure",
            ".youtube.com\tTRUE\t/accounts\tTRUE\t0\tSID\twrong-path"
        ).joinToString("\n")
        assertEquals("SAPISID=synthetic", parseMusicSessionCookie(input))
    }
    @Test fun rejectsMissingAuthenticationHeaderInjectionAndOversizedInput() {
        for (input in listOf("SID=example", "SAPISID=synthetic\r\nAuthorization: injected", "x".repeat(65_537), """{"Authorization":"Bearer example"}""")) {
            assertTrue(runCatching { parseMusicSessionCookie(input) }.isFailure)
        }
    }
    @Test fun requiresActualActiveAccountInsteadOfArbitraryNames() {
        assertNull(parseMusicAccount(Json.parseToJsonElement("""{"accountName":{"simpleText":"Sign in"}}""")))
        val account = parseMusicAccount(Json.parseToJsonElement("""{"header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Test account"}]},"accountPhoto":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/example"}]}}}}"""))
        assertEquals("Test account", account?.name)
        assertEquals("https://lh3.googleusercontent.com/example", account?.avatar)
    }
    @Test fun ignoresUntrustedAvatarUrlsAndRedactsStoredCredentials() {
        val account = parseMusicAccount(Json.parseToJsonElement("""{"activeAccountHeaderRenderer":{"accountName":{"simpleText":"Test"},"accountPhoto":{"thumbnails":[{"url":"https://example.com/avatar"}]}}}"""))
        assertNull(account?.avatar)
        assertFalse(StoredMusicSession("private-value", "0", "Name", null).toString().contains("private-value"))
    }
}
