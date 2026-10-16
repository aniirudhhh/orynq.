package com.theveloper.pixelplay.presentation.youtube

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import kotlinx.coroutines.delay
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.theveloper.pixelplay.data.youtube.parseMusicSessionCookie
import com.theveloper.pixelplay.data.youtube.YouTubeSessionStore
import com.theveloper.pixelplay.data.youtube.StoredMusicSession

/** Dedicated process/profile. No injected scripts, password access, JS bridge, or UA spoofing. */
class YouTubeLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var closing = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(false)
        YouTubeSessionStore(this, PENDING_SESSION).clear()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                var ready by remember { mutableStateOf(false) }
                var loading by remember { mutableStateOf(true) }
                var message by remember { mutableStateOf<String?>(null) }
                var origin by remember { mutableStateOf("music.youtube.com") }
                var navigationVersion by remember { mutableIntStateOf(0) }
                LaunchedEffect(loading, navigationVersion) {
                    if (loading) {
                        delay(30_000L)
                        loading = false
                        message = "This page is taking too long. You can return to YouTube Music to continue, or use session import."
                    }
                }
                BackHandler {
                    val view = browser
                    if (view?.canGoBack() == true) view.goBack() else finish()
                }
                Scaffold(topBar = {
                    TopAppBar(title = { Text("YouTube Music", maxLines = 1) }, navigationIcon = {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel sign-in") }
                    })
                }, bottomBar = {
                    Surface {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message ?: "Sign in on Google's page, return to YouTube Music, then connect.",
                                style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                val view = browser ?: return@Button
                                if (Uri.parse(view.url.orEmpty()).host != "music.youtube.com") {
                                    message = "Return to YouTube Music after signing in."
                                    view.loadUrl(MUSIC_URL)
                                    return@Button
                                }
                                val cookie = CookieManager.getInstance().getCookie(MUSIC_URL).orEmpty()
                                if (runCatching { parseMusicSessionCookie(cookie) }.isFailure) {
                                    message = "No signed-in YouTube session found yet. Complete sign-in first, or try session import."
                                } else {
                                    val authUser = Uri.parse(view.url.orEmpty()).getQueryParameter("authuser")
                                        ?.takeIf { Regex("[0-9]").matches(it) } ?: "0"
                                    try {
                                        // Encrypted one-time handoff: credentials never enter Intent/saved-state extras.
                                        YouTubeSessionStore(this@YouTubeLoginActivity, PENDING_SESSION)
                                            .write(StoredMusicSession(cookie, authUser, "", null))
                                        closeWithResult(Activity.RESULT_OK, null)
                                    } catch (_: Exception) {
                                        message = "Couldn't securely transfer the session. Try session import instead."
                                    }
                                }
                            }, enabled = !loading && !closing, modifier = Modifier.fillMaxWidth()) { Text(if (ready) "Connect this account" else "Return to YouTube Music") }
                            TextButton(onClick = { closeWithResult(RESULT_IMPORT, null) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Sign-in blocked? Use session import")
                            }
                        }
                    }
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text("https://$origin", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                            WebView(context).apply {
                                browser = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.setSupportMultipleWindows(false)
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.saveFormData = false
                                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, progress: Int) {
                                        if (progress == 100) {
                                            loading = false
                                            ready = Uri.parse(view.url.orEmpty()).host == "music.youtube.com"
                                        }
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        if (!isAllowedMusicLoginUrl(request.url.toString())) {
                                            if (request.isForMainFrame) {
                                                loading = false
                                                ready = false
                                                message = "Couldn't continue to ${request.url.host.orEmpty().take(120)} inside the sign-in window. Return to YouTube Music or use session import."
                                            }
                                            return true
                                        }
                                        return false
                                    }
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                        if (request.isForMainFrame && !isAllowedMusicLoginUrl(request.url.toString())) {
                                            view.post {
                                                loading = false
                                                ready = false
                                                message = "A redirect was blocked. Return to YouTube Music or use session import."
                                            }
                                            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                                        }
                                        return null
                                    }
                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        navigationVersion++
                                        loading = true
                                        ready = false
                                        message = null
                                        origin = Uri.parse(url.orEmpty()).host ?: "music.youtube.com"
                                        if (url.orEmpty().contains("disallowed_useragent")) message = "Google blocked embedded sign-in. Use session import below."
                                    }
                                    override fun onPageCommitVisible(view: WebView, url: String?) {
                                        loading = false
                                        ready = Uri.parse(url.orEmpty()).host == "music.youtube.com"
                                    }
                                    override fun onPageFinished(view: WebView, url: String?) {
                                        loading = false
                                        ready = Uri.parse(url.orEmpty()).host == "music.youtube.com"
                                    }
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame) { loading = false; message = "Couldn't load the sign-in page. Check your connection or use session import." }
                                    }
                                }
                                // Start each attempt with an empty isolated cookie jar.
                                CookieManager.getInstance().removeAllCookies {
                                    if (!isFinishing && !isDestroyed) {
                                        WebStorage.getInstance().deleteAllData()
                                        clearCache(true)
                                        loadUrl(MUSIC_URL)
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    private fun closeWithResult(code: Int, result: Intent?) {
        if (closing) return
        closing = true
        browser?.apply { stopLoading(); removeAllViews(); destroy() }
        browser = null
        // Only a success/fallback status crosses the non-exported activity boundary.
        CookieManager.getInstance().removeAllCookies {
            WebStorage.getInstance().deleteAllData()
            if (result == null) setResult(code) else setResult(code, result)
            finish()
        }
    }

    override fun onDestroy() {
        browser?.apply { stopLoading(); clearHistory(); clearCache(true); removeAllViews(); destroy() }
        browser = null
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }

    companion object {
        const val MUSIC_URL = "https://music.youtube.com/"
        const val PENDING_SESSION = "youtube_music_pending_session"
        const val RESULT_IMPORT = Activity.RESULT_FIRST_USER
    }
}

internal fun isAllowedMusicLoginUrl(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    val trustedSite = uri.host in setOf("music.youtube.com", "www.youtube.com", "m.youtube.com", "accounts.youtube.com",
        "accounts.google.com", "accounts.google.co.in", "consent.google.com", "consent.youtube.com")
    val cookieHandoff = uri.host in setOf("www.google.com", "google.com", "www.google.co.in") &&
        uri.path.orEmpty().startsWith("/accounts/")
    uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) && (trustedSite || cookieHandoff)
}.getOrDefault(false)
