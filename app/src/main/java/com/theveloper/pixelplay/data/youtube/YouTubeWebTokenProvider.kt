package com.theveloper.pixelplay.data.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import com.metrolist.innertubex.extraction.*
import com.metrolist.innertubex.extraction.potoken.*
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Anonymous page attestation, adapted from InnerTubeX's harness. Tokens stay in memory. */
internal class YouTubeWebTokenProvider(private val context: Context, private val http: HttpClient) : TokenProvider {
    override val capabilities = TokenProviderCapabilities(setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION), usesWebView = true)
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var visitor: String? = null
    private var playerToken: String? = null
    private var expiresAt = 0L
    private val json = Json
    private val agent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

    override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
            require(visitorData.isNotBlank() && visitorData.length <= 4096)
            // Account-cookie support needs a separate authenticated session lifecycle.
            if (cookie != null) return@withLock null
            var stage = "bootstrap"
            try {
                if (visitor != visitorData || System.currentTimeMillis() >= expiresAt) reset()
                ensureRuntime()
                if (playerToken == null) {
                    stage = "watch"
                    val page = http.get("https://www.youtube.com/watch") {
                        parameter("v", videoId)
                        header(HttpHeaders.UserAgent, agent)
                        header(HttpHeaders.Accept, "text/html")
                        header("X-Goog-Visitor-Id", visitorData)
                        header(HttpHeaders.Cookie, "SOCS=CAI")
                    }.limited(4 * 1024 * 1024)
                    val attestation = parseWebPageAttestationContext(page)
                    stage = "interpreter"
                    val interpreterUrl = requireTrustedAttestationInterpreterUrl(parseAttestationInterpreterUrl(attestation.challenge))
                    val interpreter = http.get(interpreterUrl) { header(HttpHeaders.UserAgent, agent) }.limited(8 * 1024 * 1024)
                    val challenge = parseAttestationChallengeData(attestation.challenge, interpreter)
                    stage = "browser"
                    evaluate("""
                        globalThis.yt={config_:{EVENT_ID:${json.encodeToString(attestation.eventId)}}};
                        globalThis.__po={state:'running'};
                        runBotGuard($challenge).then(r=>{globalThis.__signal=r.webPoSignalOutput;
                        globalThis.__po={state:'ready',value:r.botguardResponse};})
                        .catch(()=>{globalThis.__po={state:'error'};}); 'started';
                    """.trimIndent())
                    val snapshot = awaitResult()
                    stage = "integrity"
                    val raw = http.post("https://www.youtube.com/api/jnn/v1/GenerateIT") {
                        contentType(ContentType.parse("application/json+protobuf"))
                        header(HttpHeaders.Accept, "application/json")
                        header(HttpHeaders.UserAgent, agent)
                        header("x-goog-api-key", "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw")
                        header("x-user-agent", "grpc-web-javascript/0.1")
                        setBody("[\"O43z0dpjhgX20SCx4KAo\",${json.encodeToString(snapshot)}]")
                    }.limited(1024 * 1024)
                    val integrity = parseIntegrityTokenData(raw)
                    stage = "minter"
                    evaluate("""
                        globalThis.__po={state:'running'};
                        createPoTokenMinter(globalThis.__signal,${integrity.tokenJavaScript})
                        .then(()=>{globalThis.__po={state:'ready',value:'ready'};})
                        .catch(()=>{globalThis.__po={state:'error'};}); 'started';
                    """.trimIndent())
                    awaitResult()
                    val lifetime = integrity.lifetimeSeconds.coerceAtMost(6 * 3600)
                    require(lifetime > 1)
                    expiresAt = System.currentTimeMillis() + (lifetime - minOf(600L, (lifetime / 10).coerceAtLeast(1))) * 1000
                    playerToken = mint(visitorData)
                    visitor = visitorData
                }
                stage = "video"
                val streamingToken = mint(videoId)
                check(System.currentTimeMillis() < expiresAt)
                timber.log.Timber.tag("YouTubeToken").d("Anonymous music attestation ready")
                PoTokenResult(checkNotNull(playerToken), streamingToken, visitorData)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { reset() }
                throw cancelled
            } catch (error: Exception) {
                reset()
                // No remote JavaScript, request URLs, cookies, or attestation values escape into logs.
                timber.log.Timber.tag("YouTubeToken").w("Attestation failed stage=%s type=%s", stage, error::class.java.simpleName)
                null
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureRuntime() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext
        val loaded = CompletableDeferred<Unit>()
        val view = WebView(context.applicationContext)
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            userAgentString = agent
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                loaded.completeExceptionally(IllegalStateException("Token renderer unavailable"))
                view.destroy()
                webView = null
                return true
            }
        }
        val html = withContext(Dispatchers.IO) { context.assets.open("youtube_token_runtime.html").bufferedReader().use { it.readText() } }
        view.loadDataWithBaseURL("https://www.youtube.com/robots.txt", html, "text/html", "utf-8", null)
        withTimeout(10_000) { loaded.await() }
    }

    private suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
        withTimeout(5_000) {
            suspendCancellableCoroutine { continuation ->
                checkNotNull(webView).evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(runCatching {
                        json.parseToJsonElement(value).jsonPrimitive.content
                    }.getOrDefault(""))
                }
            }
        }
    }

    private suspend fun awaitResult(): String = withTimeout(17_000) {
        while (true) {
            val result = json.parseToJsonElement(evaluate("JSON.stringify(globalThis.__po || null)")).jsonObject
            when (result["state"]?.jsonPrimitive?.content) {
                "ready" -> return@withTimeout result["value"]?.jsonPrimitive?.content.orEmpty()
                "error" -> error("Token computation failed")
            }
            delay(30)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun mint(identifier: String): String {
        evaluate("""
            globalThis.__po={state:'running'};
            obtainPoToken(${stringToU8(identifier)}).then(t=>{
            globalThis.__po={state:'ready',value:Array.from(t).join(',')};})
            .catch(()=>{globalThis.__po={state:'error'};}); 'started';
        """.trimIndent())
        return u8ToBase64(awaitResult())
    }

    private suspend fun io.ktor.client.statement.HttpResponse.limited(limit: Int): String {
        check(status.isSuccess()) { "Token HTTP failure" }
        headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { require(it <= limit) }
        return bodyAsText().also { require(it.length <= limit) }
    }

    private suspend fun reset() = withContext(Dispatchers.Main) {
        webView?.destroy()
        webView = null
        visitor = null
        playerToken = null
        expiresAt = 0
    }

    override suspend fun invalidateAttestation() { mutex.withLock { reset() } }
    override suspend fun close() { mutex.withLock { reset() } }
}
